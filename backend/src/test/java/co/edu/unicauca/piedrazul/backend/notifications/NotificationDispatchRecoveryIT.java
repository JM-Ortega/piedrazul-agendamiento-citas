package co.edu.unicauca.piedrazul.backend.notifications;

import co.edu.unicauca.piedrazul.backend.notifications.application.NotificationDispatcherService;
import co.edu.unicauca.piedrazul.backend.notifications.application.ProviderRegistry;
import co.edu.unicauca.piedrazul.backend.notifications.application.RecipientEmailPolicy;
import co.edu.unicauca.piedrazul.backend.notifications.application.template.TemplateRenderer;
import co.edu.unicauca.piedrazul.backend.notifications.domain.message.ChannelMessage;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.*;
import co.edu.unicauca.piedrazul.backend.notifications.domain.policy.ChannelFallbackPolicy;
import co.edu.unicauca.piedrazul.backend.notifications.domain.policy.InterruptedDispatchPolicy;
import co.edu.unicauca.piedrazul.backend.notifications.domain.policy.InterruptedDispatchPolicy.Resolution;
import co.edu.unicauca.piedrazul.backend.notifications.domain.policy.RetryPolicy;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.*;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.NotificationDispatchStore.Recovery;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.mappers.NotificationAttemptMapper;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.mappers.NotificationMapper;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.mappers.NotificationScheduleMapper;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.persistence.adapter.NotificationAttemptRepositoryAdapter;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.persistence.adapter.NotificationDispatchStoreAdapter;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.persistence.adapter.NotificationRepositoryAdapter;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.persistence.adapter.NotificationScheduleRepositoryAdapter;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.scheduler.ScheduleClaimService;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.scheduler.StaleNotificationAttemptJob;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.template.InMemoryNotificationTemplateProvider;
import co.edu.unicauca.piedrazul.backend.support.PostgresIntegrationSupport;
import jakarta.validation.Validation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Recuperación del trabajo interrumpido sobre PostgreSQL real: una interrupción por cada ventana
 * del despacho, carreras entre trabajadores y recuperación, y reversión de transacciones. Las
 * carreras se ordenan con barreras y puertas; ninguna depende de esperas por tiempo. Datos
 * exclusivamente sintéticos.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        NotificationDispatchStoreAdapter.class,
        NotificationRepositoryAdapter.class,
        NotificationScheduleRepositoryAdapter.class,
        NotificationAttemptRepositoryAdapter.class,
        NotificationMapper.class,
        NotificationScheduleMapper.class,
        NotificationAttemptMapper.class,
        ScheduleClaimService.class
})
// Sin transacción envolvente del test: aquí se comprueban fronteras reales.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ExtendWith(OutputCaptureExtension.class)
class NotificationDispatchRecoveryIT extends PostgresIntegrationSupport {

    private static final String SYNTHETIC_CODE = "000111";
    private static final Instant LONG_AGO = Instant.parse("2000-01-01T00:00:00Z");
    private static final Duration THRESHOLD = Duration.ofHours(2);
    private static final int RACE_ROUNDS = 25;
    private static final int WORKERS = 8;

    @Autowired
    private NotificationDispatchStore store;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private NotificationScheduleRepository scheduleRepository;

    @Autowired
    private NotificationAttemptRepository attemptRepository;

    @Autowired
    private ScheduleClaimService claimService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    private final ExecutorService workers = Executors.newCachedThreadPool();
    private UUID personId;

    @BeforeEach
    void isolateFromOtherClasses() {
        // La base se comparte entre clases: nada activo de otras pruebas entra en estos reclamos.
        jdbc.update("UPDATE piedrazul.notification_attempts SET status = 'CANCELLED' WHERE status = 'PROCESSING'");
        jdbc.update("""
                UPDATE piedrazul.notification_schedule SET status = 'CANCELLED', cancelled_at = now()
                WHERE status IN ('PENDING', 'PROCESSING')""");

        personId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO piedrazul.person (id, identification_type, identification, first_name, last_name, phone, email)
                VALUES (?, 'CEDULA', ?, 'Sintético', 'Prueba', '+570000000000', 'sintetico@example.test')""",
                personId, "w1b-" + personId);
    }

    @AfterEach
    void stopWorkers() {
        workers.shutdownNow();
    }

    // ── Ventanas de interrupción ────────────────────────────────────────

    @Test
    void crashAfterClaimRequeuesAndTheNextPassDelivers() {
        Notification notification = seedOtp();
        ClaimedSchedule claim = claim();

        assertThat(recover(claim).resolution()).isEqualTo(Resolution.REQUEUED);

        assertState(notification, "PROCESSING", "PENDING");
        assertThat(attemptStatuses(notification)).isEmpty();
        assertThat(variablesJson(notification)).contains(SYNTHETIC_CODE);

        Stub email = Stub.email(Stub::accepted);
        dispatcher(email).dispatch(claim());

        assertThat(email.calls()).isEqualTo(1);
        assertState(notification, "ACCEPTED", "SENT");
        assertThat(attemptStatuses(notification)).containsExactly("ACCEPTED");
        assertCodeGone(notification);
    }

    @Test
    void reminderWithoutExpiryIsRequeuedEvenLongAfterItsClaim() {
        Notification notification = seedReminder();
        ClaimedSchedule claim = claim();
        backdate(notification);

        // La recuperación no introduce ninguna ventana de entrega tardía.
        assertThat(store.recover(claim.scheduleId(), Instant.now().minus(THRESHOLD),
                Instant.now().plus(Duration.ofDays(30))).resolution()).isEqualTo(Resolution.REQUEUED);
        assertState(notification, "PROCESSING", "PENDING");
    }

    @Test
    void databaseFailureDuringAttemptInsertLeavesAClaimThatIsRequeued() {
        Notification notification = seedOtp();
        ClaimedSchedule claim = claim();
        Stub email = Stub.email(Stub::accepted);

        // La base deja de responder tras tomar el trabajo: tampoco puede cerrarse localmente.
        dispatcher(new FaultyStore(write -> {
            throw new IllegalStateException("base sintética caída");
        }), Clock.systemUTC(), email).dispatch(claim);

        assertThat(email.calls()).isZero();
        assertState(notification, "PROCESSING", "PROCESSING");
        assertThat(attemptStatuses(notification)).isEmpty();

        assertThat(recover(claim).resolution()).isEqualTo(Resolution.REQUEUED);
    }

    @Test
    void crashBeforeTheProviderIsTreatedLikeAPossibleHandoff() {
        Notification notification = seedOtp();
        ClaimedSchedule claim = claim();
        inFlightAttempt(notification, claim);

        // No se puede distinguir de una caída posterior a la entrega: no se reenvía.
        Recovery recovery = recover(claim);

        assertThat(recovery.resolution()).isEqualTo(Resolution.ACCEPTANCE_UNKNOWN);
        assertThat(recovery.closedAttempts()).singleElement();
        assertUnknownTerminal(notification);
        assertNoReplay(notification);
    }

    @Test
    void crashDuringHandoffBecomesUnknownWithoutReplay() {
        Notification notification = seedOtp();
        ClaimedSchedule claim = claim();
        Stub email = Stub.email(() -> {
            throw new Error("el proceso muere durante la entrega");
        });

        assertThatThrownBy(() -> dispatcher(email).dispatch(claim)).isInstanceOf(Error.class);

        assertState(notification, "PROCESSING", "PROCESSING");
        assertThat(attemptStatuses(notification)).containsExactly("PROCESSING");

        assertThat(recover(claim).resolution()).isEqualTo(Resolution.ACCEPTANCE_UNKNOWN);
        assertThat(email.calls()).isEqualTo(1);
        assertUnknownTerminal(notification);
        assertNoReplay(notification);
    }

    @Test
    void rolledBackTerminalWriteIsRecoveredAsUnknown() {
        Notification notification = seedOtp();
        ClaimedSchedule claim = claim();
        Stub email = Stub.email(Stub::accepted);

        withFailingUpdate("notification_attempts", "NEW.status = 'ACCEPTED'",
                () -> dispatcher(email).dispatch(claim));

        // La escritura terminal se revirtió entera: el proveedor ya aceptó, pero nada lo prueba.
        assertThat(email.calls()).isEqualTo(1);
        assertState(notification, "PROCESSING", "PROCESSING");
        assertThat(attemptStatuses(notification)).containsExactly("PROCESSING");
        assertThat(variablesJson(notification)).contains(SYNTHETIC_CODE);

        assertThat(recover(claim).resolution()).isEqualTo(Resolution.ACCEPTANCE_UNKNOWN);
        assertUnknownTerminal(notification);
        assertNoReplay(notification);
    }

    @Test
    void crashBetweenChannelsContinuesWithTheNextOneAndKeepsNumbering() {
        Notification notification = seedOtp(new ChannelPreference(
                List.of(NotificationChannel.EMAIL, NotificationChannel.SMS)));
        ClaimedSchedule claim = claim();
        Stub email = Stub.email(() -> Stub.failure(FailureType.PERMANENT));
        Stub sms = new Stub(NotificationChannel.SMS, Stub::accepted);

        // El rechazo del correo se confirma; el proceso cae antes de empezar con SMS.
        dispatcher(new FaultyStore(write -> {
            if (write.newAttempt() != null && write.newAttempt().getChannel() == NotificationChannel.SMS) {
                throw new IllegalStateException("base sintética caída");
            }

            if (write.finish() == DispatchFinish.FAILED) {
                throw new IllegalStateException("base sintética caída");
            }
        }), Clock.systemUTC(), email, sms).dispatch(claim);

        assertThat(attemptStatuses(notification)).containsExactly("FAILED");
        assertThat(recover(claim).resolution()).isEqualTo(Resolution.REQUEUED);

        dispatcher(email, sms).dispatch(claim());

        assertThat(email.calls()).isEqualTo(1);
        assertThat(sms.calls()).isEqualTo(1);
        assertThat(jdbc.queryForList("""
                SELECT channel || ':' || attempt_number FROM piedrazul.notification_attempts
                WHERE notification_id = ? ORDER BY created_at""", String.class, notification.getId()))
                .containsExactly("EMAIL:1", "SMS:1");
        assertState(notification, "ACCEPTED", "SENT");
    }

    @Test
    void crashDuringARetryRequeuesWithoutConsumingTheRetryBudget() {
        // Vigencia amplia: con 5 min el reintento de 300 s ya no se programaría.
        Notification notification = seedOtp(new ChannelPreference(List.of(NotificationChannel.EMAIL)), Duration.ofHours(1));
        Stub email = Stub.email(() -> Stub.failure(FailureType.TEMPORARY));

        dispatcher(email).dispatch(claim());
        assertState(notification, "PROCESSING", "PENDING");

        // Cada reintento se interrumpe una vez antes de volver a intentarse.
        for (int attempt = 2; attempt <= 3; attempt++) {
            makeRetryDue(notification);
            ClaimedSchedule interrupted = claim();
            assertThat(recover(interrupted).resolution()).isEqualTo(Resolution.REQUEUED);
            assertThat(attemptStatuses(notification)).hasSize(attempt - 1);

            dispatcher(email).dispatch(claim());
        }

        // Tres intentos reales, los mismos que sin interrupciones; el presupuesto se agota igual.
        assertThat(email.calls()).isEqualTo(3);
        assertThat(attemptStatuses(notification)).containsExactly("FAILED", "FAILED", "FAILED");
        assertState(notification, "FAILED", "FAILED");
        assertCodeGone(notification);
    }

    @Test
    void failedRecoveryRollsBackEntirelyAndCanBeRepeated() {
        Notification notification = seedOtp();
        ClaimedSchedule claim = claim();
        inFlightAttempt(notification, claim);

        withFailingUpdate("notification_schedule", "NEW.status = 'FAILED'", () ->
                assertThatThrownBy(() -> recover(claim)).isInstanceOf(RuntimeException.class));

        assertState(notification, "PROCESSING", "PROCESSING");
        assertThat(attemptStatuses(notification)).containsExactly("PROCESSING");
        assertThat(variablesJson(notification)).contains(SYNTHETIC_CODE);

        assertThat(recover(claim).resolution()).isEqualTo(Resolution.ACCEPTANCE_UNKNOWN);
        assertUnknownTerminal(notification);
    }

    // ── Aceptación y estado ya resuelto ─────────────────────────────────

    @Test
    void acceptedAttemptIsNeverDowngraded() {
        Notification notification = seedOtp();
        ClaimedSchedule claim = claim();
        NotificationAttempt attempt = inFlightAttempt(notification, claim);
        // Aceptado y registrado por el trabajador, sin que llegara a cerrar el schedule.
        attempt.markAccepted("stub-message", Instant.now());
        attemptRepository.save(attempt);

        assertThat(recover(claim).resolution()).isEqualTo(Resolution.ACCEPTED);

        assertState(notification, "ACCEPTED", "SENT");
        assertThat(attemptStatuses(notification)).containsExactly("ACCEPTED");
        assertThat(jdbc.queryForObject("SELECT provider_message_id FROM piedrazul.notification_attempts WHERE notification_id = ?",
                String.class, notification.getId())).isEqualTo("stub-message");
        assertCodeGone(notification);
    }

    @Test
    void finishedDispatchIsNotTouched() {
        Notification notification = seedOtp();
        ClaimedSchedule claim = claim();
        dispatcher(Stub.email(Stub::accepted)).dispatch(claim);
        String before = snapshot(notification);

        assertThat(recover(claim).resolution()).isEqualTo(Resolution.NONE);

        assertThat(snapshot(notification)).isEqualTo(before);
        assertThat(store.findInterrupted(Instant.now().plusSeconds(60), 500)).doesNotContain(claim.scheduleId());
    }

    @Test
    void orphanAttemptOfACancelledScheduleIsClosedWithoutChangingTheCancellation() {
        Notification notification = seedOtp();
        ClaimedSchedule claim = claim();
        inFlightAttempt(notification, claim);
        // Cancelación durante el envío; el trabajador cae antes de poder registrar nada.
        jdbc.update("UPDATE piedrazul.notification SET status = 'CANCELLED' WHERE id = ?", notification.getId());
        jdbc.update("UPDATE piedrazul.notification_schedule SET status = 'CANCELLED' WHERE id = ?", claim.scheduleId());

        assertThat(store.findInterrupted(Instant.now().plusSeconds(60), 500)).contains(claim.scheduleId());
        assertThat(recover(claim).resolution()).isEqualTo(Resolution.ATTEMPT_CLOSED);

        assertState(notification, "CANCELLED", "CANCELLED");
        assertThat(attemptStatuses(notification)).containsExactly("UNKNOWN");
        assertCodeGone(notification);
    }

    // ── Vigencia y límites ──────────────────────────────────────────────

    @Test
    void expiredCodeIsClosedInsteadOfRequeued() {
        Notification notification = seedOtp();
        ClaimedSchedule claim = claim();

        Instant afterExpiry = Instant.now().plus(Duration.ofMinutes(6));

        assertThat(store.recover(claim.scheduleId(), afterExpiry, afterExpiry).resolution())
                .isEqualTo(Resolution.EXPIRED);

        assertState(notification, "EXPIRED", "EXPIRED");
        assertCodeGone(notification);
    }

    @Test
    void codeThatExpiresWhileRequeuedIsNeverSent() {
        Notification notification = seedOtp();
        assertThat(recover(claim()).resolution()).isEqualTo(Resolution.REQUEUED);
        Stub email = Stub.email(Stub::accepted);

        Clock afterExpiry = Clock.offset(Clock.systemUTC(), Duration.ofMinutes(6));
        dispatcher(store, afterExpiry, email).dispatch(claim());

        assertThat(email.calls()).isZero();
        assertState(notification, "EXPIRED", "EXPIRED");
        assertCodeGone(notification);
    }

    @Test
    void repeatedInterruptionsAreBounded() {
        Notification notification = seedOtp();

        for (int requeue = 0; requeue < InterruptedDispatchPolicy.MAX_REQUEUES; requeue++) {
            assertThat(recover(claim()).resolution()).isEqualTo(Resolution.REQUEUED);
        }

        assertThat(recover(claim()).resolution()).isEqualTo(Resolution.REQUEUES_EXHAUSTED);

        assertState(notification, "FAILED", "FAILED");
        assertThat(attemptStatuses(notification)).isEmpty();
        assertCodeGone(notification);
    }

    // ── Trabajo vivo y bloqueos ─────────────────────────────────────────

    @Test
    void jobLeavesLiveWorkAloneAndIsIdempotent() {
        Notification interrupted = seedOtp();
        ClaimedSchedule interruptedClaim = claim();
        backdate(interrupted);

        Notification recentlyClaimed = seedOtp();
        ClaimedSchedule recentClaim = claim();

        // Reclamo antiguo, pero con un intento reciente: sigue enviándose (posición en el lote).
        Notification slowBatchItem = seedOtp();
        ClaimedSchedule slowClaim = claim();
        inFlightAttempt(slowBatchItem, slowClaim);
        jdbc.update("UPDATE piedrazul.notification_schedule SET processing_started_at = now() - interval '3 hours' WHERE id = ?",
                slowClaim.scheduleId());

        StaleNotificationAttemptJob job = job(store);
        job.processStaleAttempts();
        job.processStaleAttempts();
        job.processStaleAttempts();

        assertState(interrupted, "PROCESSING", "PENDING");
        assertThat(variablesJson(interrupted)).contains("\"dispatchRecoveries\":\"1\"");
        assertThat(interruptedClaim.attemptCount()).isZero();

        assertState(recentlyClaimed, "PENDING", "PROCESSING");
        assertThat(attemptStatuses(recentlyClaimed)).isEmpty();
        assertThat(recentClaim.attemptCount()).isZero();

        assertState(slowBatchItem, "PROCESSING", "PROCESSING");
        assertThat(attemptStatuses(slowBatchItem)).containsExactly("PROCESSING");
    }

    @Test
    void aScheduleThatCannotBeResolvedDoesNotBlockTheRest(CapturedOutput output) {
        Notification poisoned = seedOtp();
        claim();
        backdate(poisoned);
        Notification healthy = seedOtp();
        claim();
        backdate(healthy);
        jdbc.update("UPDATE piedrazul.notification SET variables_json = 'ilegible' WHERE id = ?", poisoned.getId());

        try {
            job(store).processStaleAttempts();

            assertState(healthy, "PROCESSING", "PENDING");
            assertState(poisoned, "PENDING", "PROCESSING");
            assertThat(output).contains("NOTIFICATION_DISPATCH_INTERRUPTED").contains("stage=recovery");
        } finally {
            jdbc.update("UPDATE piedrazul.notification SET variables_json = '{}' WHERE id = ?", poisoned.getId());
        }
    }

    @Test
    void noLockOrTransactionIsHeldDuringTheProviderCall() {
        Notification notification = seedOtp();
        ClaimedSchedule claim = claim();
        List<Integer> transactionsDuringSend = new ArrayList<>();
        Stub email = Stub.email(() -> {
            // NOWAIT falla de inmediato si el despachador retuviera alguna de las filas.
            jdbc.queryForList("SELECT id FROM piedrazul.notification_schedule WHERE id = ? FOR UPDATE NOWAIT",
                    claim.scheduleId());
            jdbc.queryForList("SELECT id FROM piedrazul.notification WHERE id = ? FOR UPDATE NOWAIT",
                    notification.getId());
            transactionsDuringSend.add(jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE datname = current_database() AND state LIKE 'idle in transaction%'""", Integer.class));
            return Stub.accepted();
        });

        dispatcher(email).dispatch(claim);

        assertThat(email.calls()).isEqualTo(1);
        assertThat(transactionsDuringSend).containsExactly(0);
        assertState(notification, "ACCEPTED", "SENT");
    }

    @Test
    void recoveryWaitsForTheScheduleLockAndDecidesOnWhatItThenSees() throws Exception {
        Notification notification = seedOtp();
        ClaimedSchedule claim = claim();
        NotificationDispatchStore.Begin begin = store.begin(claim.scheduleId(), 0, Instant.now());
        assertThat(begin.status()).isEqualTo(NotificationDispatchStore.Begin.Status.READY);

        Future<Recovery> recovery = new TransactionTemplate(transactionManager).execute(tx -> {
            // Una transacción del despacho retiene la fila y añade un intento mientras tanto.
            jdbc.queryForList("SELECT id FROM piedrazul.notification_schedule WHERE id = ? FOR UPDATE", claim.scheduleId());

            Future<Recovery> blocked = workers.submit(() ->
                    store.recover(claim.scheduleId(), Instant.now().minusSeconds(60), Instant.now()));

            awaitLockWaiters(1);
            assertThat(blocked).isNotDone();

            jdbc.update("""
                    INSERT INTO piedrazul.notification_attempts
                        (id, notification_id, schedule_id, channel, provider_name, status, attempt_number, created_at, updated_at)
                    VALUES (?, ?, ?, 'EMAIL', 'stub-email', 'PROCESSING', 1, now(), now())""",
                    UUID.randomUUID(), notification.getId(), claim.scheduleId());
            jdbc.update("UPDATE piedrazul.notification_schedule SET processing_started_at = now() - interval '3 hours' WHERE id = ?",
                    claim.scheduleId());
            return blocked;
        });

        // Vio el intento recién confirmado, no el estado de cuando empezó a esperar.
        assertThat(recovery.get(30, TimeUnit.SECONDS).resolution()).isEqualTo(Resolution.NONE);
        assertThat(attemptStatuses(notification)).containsExactly("PROCESSING");
        assertState(notification, "PROCESSING", "PROCESSING");
    }

    // ── Carreras ────────────────────────────────────────────────────────

    @Test
    void workerThatResumesAfterTheRequeueNeverInvokesTheProvider() throws Exception {
        Notification notification = seedOtp();
        ClaimedSchedule claim = claim();
        Stub email = Stub.email(Stub::accepted);
        CountDownLatch paused = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);

        // El trabajador se detiene justo antes de insertar su intento en curso.
        Future<?> worker = workers.submit(() -> dispatcher(new FaultyStore(write -> {
            paused.countDown();
            await(resume);
        }), Clock.systemUTC(), email).dispatch(claim));

        await(paused);
        assertThat(recover(claim).resolution()).isEqualTo(Resolution.REQUEUED);
        resume.countDown();
        worker.get(30, TimeUnit.SECONDS);

        assertThat(email.calls()).isZero();
        assertThat(attemptStatuses(notification)).isEmpty();
        assertState(notification, "PROCESSING", "PENDING");
    }

    @Test
    void lateWorkerCannotOverwriteTheRecoveredOutcome(CapturedOutput output) throws Exception {
        Notification notification = seedOtp();
        ClaimedSchedule claim = claim();
        CountDownLatch inProvider = new CountDownLatch(1);
        CountDownLatch answer = new CountDownLatch(1);
        Stub email = Stub.email(() -> {
            inProvider.countDown();
            await(answer);
            return Stub.accepted();
        });

        Future<?> worker = workers.submit(() -> dispatcher(email).dispatch(claim));
        await(inProvider);

        // Mientras responde, la recuperación no lo toca si el intento es reciente...
        assertThat(store.recover(claim.scheduleId(), Instant.now().minusSeconds(60), Instant.now()).resolution())
                .isEqualTo(Resolution.NONE);
        assertThat(attemptStatuses(notification)).containsExactly("PROCESSING");

        // ...y lo cierra de forma conservadora cuando supera el umbral.
        assertThat(recover(claim).resolution()).isEqualTo(Resolution.ACCEPTANCE_UNKNOWN);
        String recovered = snapshot(notification);

        answer.countDown();
        worker.get(30, TimeUnit.SECONDS);

        assertThat(email.calls()).isEqualTo(1);
        assertThat(snapshot(notification)).isEqualTo(recovered);
        assertUnknownTerminal(notification);
        assertThat(output).contains("NOTIFICATION_LATE_ACCEPTANCE").contains("stub-message")
                .doesNotContain(SYNTHETIC_CODE);
    }

    @Test
    void recoveryRacingDispatchNeverCausesASecondInvocation() throws Exception {
        for (int round = 0; round < RACE_ROUNDS; round++) {
            Notification notification = seedOtp();
            ClaimedSchedule claim = claim();
            Stub email = Stub.email(Stub::accepted);
            CyclicBarrier start = new CyclicBarrier(2);

            Future<?> worker = workers.submit(() -> {
                await(start);
                dispatcher(email).dispatch(claim);
            });
            Future<?> recovery = workers.submit(() -> {
                await(start);
                recover(claim);
            });

            worker.get(30, TimeUnit.SECONDS);
            recovery.get(30, TimeUnit.SECONDS);

            // Lo que quedó reencolado lo retoma una pasada posterior: sigue siendo una sola entrega.
            claimService.claimDueSchedules(Instant.now(), 1)
                    .forEach(next -> dispatcher(email).dispatch(next));

            assertThat(email.calls()).as("ronda %d", round).isEqualTo(1);
            assertThat(attemptStatuses(notification)).as("ronda %d", round).hasSize(1);
            assertThat(attemptStatuses(notification).get(0)).isIn("ACCEPTED", "UNKNOWN");
            assertThat(statusOf(notification)).isIn("ACCEPTED", "FAILED");
            assertCodeGone(notification);
        }
    }

    @Test
    void twoWorkersWithTheSameFenceInvokeTheProviderOnce() throws Exception {
        for (int round = 0; round < RACE_ROUNDS; round++) {
            Notification notification = seedOtp();
            // Reclamo interrumpido, reencolado y vuelto a reclamar: ambos ven cero intentos.
            ClaimedSchedule late = claim();
            assertThat(recover(late).resolution()).isEqualTo(Resolution.REQUEUED);
            ClaimedSchedule current = claim();
            assertThat(current.attemptCount()).isEqualTo(late.attemptCount());

            Stub email = Stub.email(Stub::accepted);
            CyclicBarrier start = new CyclicBarrier(2);

            List<Future<?>> dispatches = List.of(late, current).stream()
                    .<Future<?>>map(claim -> workers.submit(() -> {
                        await(start);
                        dispatcher(email).dispatch(claim);
                    }))
                    .toList();

            for (Future<?> dispatch : dispatches) {
                dispatch.get(30, TimeUnit.SECONDS);
            }

            assertThat(email.calls()).as("ronda %d", round).isEqualTo(1);
            assertThat(attemptStatuses(notification)).as("ronda %d", round).containsExactly("ACCEPTED");
            assertThat(jdbc.queryForObject("SELECT attempt_number FROM piedrazul.notification_attempts WHERE notification_id = ?",
                    Integer.class, notification.getId())).isEqualTo(1);
            assertState(notification, "ACCEPTED", "SENT");
        }
    }

    @Test
    void workerWithAnObsoleteFenceCannotDisturbOneAlreadyInTheProvider() throws Exception {
        Notification notification = seedOtp();
        ClaimedSchedule late = claim();
        assertThat(recover(late).resolution()).isEqualTo(Resolution.REQUEUED);
        ClaimedSchedule current = claim();

        CountDownLatch lateAtInsert = new CountDownLatch(1);
        CountDownLatch lateMayInsert = new CountDownLatch(1);
        CountDownLatch inProvider = new CountDownLatch(1);
        CountDownLatch answer = new CountDownLatch(1);

        Stub lateEmail = Stub.email(Stub::accepted);
        Stub currentEmail = Stub.email(() -> {
            inProvider.countDown();
            await(answer);
            return Stub.accepted();
        });

        // El trabajador antiguo ya pasó begin y se detiene justo antes de insertar su intento.
        Future<?> lateWorker = workers.submit(() -> dispatcher(new FaultyStore(write -> {
            lateAtInsert.countDown();
            await(lateMayInsert);
        }), Clock.systemUTC(), lateEmail).dispatch(late));
        await(lateAtInsert);

        Future<?> currentWorker = workers.submit(() -> dispatcher(currentEmail).dispatch(current));
        await(inProvider);

        // Solo la cerca lo detiene: no es un error, y no cierra el trabajo del otro.
        lateMayInsert.countDown();
        lateWorker.get(30, TimeUnit.SECONDS);

        assertThat(lateEmail.calls()).isZero();
        assertState(notification, "PROCESSING", "PROCESSING");
        assertThat(attemptStatuses(notification)).containsExactly("PROCESSING");

        answer.countDown();
        currentWorker.get(30, TimeUnit.SECONDS);

        assertThat(currentEmail.calls()).isEqualTo(1);
        assertState(notification, "ACCEPTED", "SENT");
        assertThat(attemptStatuses(notification)).containsExactly("ACCEPTED");
    }

    @Test
    void concurrentClaimsTakeEachScheduleOnce() throws Exception {
        seedOtp();
        CyclicBarrier start = new CyclicBarrier(WORKERS);

        List<Future<List<ClaimedSchedule>>> claims = IntStream.range(0, WORKERS)
                .mapToObj(worker -> workers.submit(() -> {
                    await(start);
                    return claimService.claimDueSchedules(Instant.now(), 1);
                }))
                .toList();

        int claimed = 0;

        for (Future<List<ClaimedSchedule>> claim : claims) {
            claimed += claim.get(30, TimeUnit.SECONDS).size();
        }

        assertThat(claimed).isEqualTo(1);
    }

    @Test
    void concurrentRecoveriesResolveOnceAndSpendOneRequeue() throws Exception {
        Notification notification = seedOtp();
        ClaimedSchedule claim = claim();
        CyclicBarrier start = new CyclicBarrier(WORKERS);

        List<Future<Recovery>> recoveries = IntStream.range(0, WORKERS)
                .mapToObj(worker -> workers.submit(() -> {
                    await(start);
                    return recover(claim);
                }))
                .toList();

        List<Resolution> resolutions = new ArrayList<>();

        for (Future<Recovery> recovery : recoveries) {
            resolutions.add(recovery.get(30, TimeUnit.SECONDS).resolution());
        }

        assertThat(resolutions).containsOnlyOnce(Resolution.REQUEUED);
        assertThat(Collections.frequency(resolutions, Resolution.NONE)).isEqualTo(WORKERS - 1);
        assertThat(variablesJson(notification)).contains("\"dispatchRecoveries\":\"1\"");
    }

    // ── Soporte ─────────────────────────────────────────────────────────

    /** Cuenta como antiguo todo lo que existe: cada prueba decide qué trabajo se interrumpió. */
    private Recovery recover(ClaimedSchedule claim) {
        Instant now = Instant.now();
        return store.recover(claim.scheduleId(), now.plusSeconds(60), now);
    }

    private StaleNotificationAttemptJob job(NotificationDispatchStore jobStore) {
        return new StaleNotificationAttemptJob(jobStore, Clock.systemUTC(), true, THRESHOLD);
    }

    private Notification seedOtp() {
        return seedOtp(new ChannelPreference(List.of(NotificationChannel.EMAIL)));
    }

    private Notification seedOtp(ChannelPreference channels) {
        return seedOtp(channels, Duration.ofMinutes(5));
    }

    private Notification seedOtp(ChannelPreference channels, Duration lifetime) {
        Instant now = Instant.now();
        return seed(NotificationType.OTP_CODE, AggregateType.VERIFICATION, channels,
                Map.of(NotificationVariables.CODE, SYNTHETIC_CODE, "expirationMinutes", "5",
                        "destination", "sintetico@example.test",
                        NotificationVariables.EXPIRES_AT, now.plus(lifetime).toString()));
    }

    private Notification seedReminder() {
        return seed(NotificationType.APPOINTMENT_REMINDER_2_DAYS, AggregateType.APPOINTMENT,
                new ChannelPreference(List.of(NotificationChannel.EMAIL)),
                Map.of("patientName", "Paciente Sintético", "doctorName", "Dra. Sintética",
                        "date", "2026-01-10", "time", "08:00"));
    }

    private Notification seed(
            NotificationType type,
            AggregateType aggregateType,
            ChannelPreference channels,
            Map<String, String> variables
    ) {
        Instant now = Instant.now();
        Notification notification = Notification.create(
                type,
                new AggregateReference(aggregateType, UUID.randomUUID()),
                new RecipientSnapshot(personId, "Sintético", "+570000000000", "sintetico@example.test",
                        Locale.forLanguageTag("es-CO"), null),
                channels,
                variables,
                now
        );
        notificationRepository.save(notification);
        scheduleRepository.save(NotificationSchedule.create(notification.getId(), LONG_AGO, now));
        return notification;
    }

    private ClaimedSchedule claim() {
        List<ClaimedSchedule> claimed = claimService.claimDueSchedules(Instant.now(), 1);
        assertThat(claimed).singleElement();
        return claimed.get(0);
    }

    /** Toma el trabajo y confirma un intento en curso, sin llegar a invocar a ningún proveedor. */
    private NotificationAttempt inFlightAttempt(Notification notification, ClaimedSchedule claim) {
        store.begin(claim.scheduleId(), claim.attemptCount(), Instant.now());
        NotificationAttempt attempt = NotificationAttempt.create(notification.getId(), claim.scheduleId(),
                NotificationChannel.EMAIL, "stub-email", 1, Instant.now());
        attempt.markProcessing(Instant.now());
        assertThat(store.write(DispatchWrite.insert(claim.scheduleId(), claim.attemptCount(), attempt,
                DispatchFinish.CONTINUE, Instant.now()))).isEqualTo(NotificationDispatchStore.WriteResult.APPLIED);
        return attempt;
    }

    private void backdate(Notification notification) {
        jdbc.update("""
                UPDATE piedrazul.notification_schedule SET processing_started_at = now() - interval '3 hours'
                WHERE notification_id = ?""", notification.getId());
        jdbc.update("""
                UPDATE piedrazul.notification_attempts SET updated_at = now() - interval '3 hours'
                WHERE notification_id = ?""", notification.getId());
    }

    private void makeRetryDue(Notification notification) {
        jdbc.update("UPDATE piedrazul.notification_schedule SET next_retry_at = ? WHERE notification_id = ?",
                java.sql.Timestamp.from(LONG_AGO), notification.getId());
    }

    /** Hace fallar de verdad, dentro de PostgreSQL, un UPDATE que cumpla la condición. */
    private void withFailingUpdate(String table, String condition, Runnable action) {
        jdbc.execute("""
                CREATE FUNCTION piedrazul.w1b_synthetic_failure() RETURNS trigger AS $$
                BEGIN RAISE EXCEPTION 'fallo sintético'; END $$ LANGUAGE plpgsql""");
        jdbc.execute("CREATE TRIGGER w1b_synthetic_failure BEFORE UPDATE ON piedrazul." + table
                + " FOR EACH ROW WHEN (" + condition + ") EXECUTE FUNCTION piedrazul.w1b_synthetic_failure()");

        try {
            action.run();
        } finally {
            jdbc.execute("DROP TRIGGER w1b_synthetic_failure ON piedrazul." + table);
            jdbc.execute("DROP FUNCTION piedrazul.w1b_synthetic_failure()");
        }
    }

    private void awaitLockWaiters(int expected) {
        for (int check = 0; check < 3_000; check++) {
            Integer waiting = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE datname = current_database() AND wait_event_type = 'Lock'""", Integer.class);

            if (waiting != null && waiting >= expected) {
                return;
            }

            Thread.onSpinWait();
        }

        throw new AssertionError("La recuperación no llegó a esperar el bloqueo");
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException | BrokenBarrierException | TimeoutException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private NotificationDispatcherService dispatcher(NotificationProvider... providers) {
        return dispatcher(store, Clock.systemUTC(), providers);
    }

    private NotificationDispatcherService dispatcher(
            NotificationDispatchStore dispatchStore,
            Clock clock,
            NotificationProvider... providers
    ) {
        return new NotificationDispatcherService(
                dispatchStore,
                new ProviderRegistry(List.of(providers)),
                new TemplateRenderer(new InMemoryNotificationTemplateProvider()),
                new RetryPolicy(),
                new ChannelFallbackPolicy(),
                new RecipientEmailPolicy(Validation.buildDefaultValidatorFactory().getValidator()),
                appointmentId -> true,
                false,
                clock
        );
    }

    private void assertState(Notification notification, String notificationStatus, String scheduleStatus) {
        assertThat(statusOf(notification)).isEqualTo(notificationStatus);
        assertThat(jdbc.queryForObject("SELECT status FROM piedrazul.notification_schedule WHERE notification_id = ?",
                String.class, notification.getId())).isEqualTo(scheduleStatus);
    }

    private void assertUnknownTerminal(Notification notification) {
        assertState(notification, "FAILED", "FAILED");
        assertThat(jdbc.queryForMap("""
                SELECT status, failure_type, error_code, failed_at FROM piedrazul.notification_attempts
                WHERE notification_id = ?""", notification.getId()))
                .containsEntry("status", "UNKNOWN")
                .containsEntry("failure_type", "UNKNOWN")
                .containsEntry("error_code", "ACCEPTANCE_UNKNOWN")
                .containsEntry("failed_at", null);
        assertCodeGone(notification);
    }

    /** Ni una pasada de reclamo ni una de recuperación vuelven a tocar el trabajo cerrado. */
    private void assertNoReplay(Notification notification) {
        String closed = snapshot(notification);

        assertThat(claimService.claimDueSchedules(Instant.now(), 10)).isEmpty();
        assertThat(store.findInterrupted(Instant.now().plusSeconds(60), 500)).isEmpty();
        assertThat(snapshot(notification)).isEqualTo(closed);
    }

    private void assertCodeGone(Notification notification) {
        assertThat(variablesJson(notification))
                .doesNotContain(SYNTHETIC_CODE)
                .doesNotContain("\"" + NotificationVariables.CODE + "\"");
    }

    private String statusOf(Notification notification) {
        return jdbc.queryForObject("SELECT status FROM piedrazul.notification WHERE id = ?",
                String.class, notification.getId());
    }

    private List<String> attemptStatuses(Notification notification) {
        return jdbc.queryForList("SELECT status FROM piedrazul.notification_attempts WHERE notification_id = ? ORDER BY created_at",
                String.class, notification.getId());
    }

    private String variablesJson(Notification notification) {
        return jdbc.queryForObject("SELECT variables_json FROM piedrazul.notification WHERE id = ?",
                String.class, notification.getId());
    }

    private String snapshot(Notification notification) {
        return jdbc.queryForList("""
                SELECT n.status, n.updated_at, n.variables_json, s.status, s.updated_at, s.next_retry_at, a.status, a.updated_at
                FROM piedrazul.notification n
                JOIN piedrazul.notification_schedule s ON s.notification_id = n.id
                LEFT JOIN piedrazul.notification_attempts a ON a.notification_id = n.id
                WHERE n.id = ? ORDER BY a.created_at""", notification.getId()).toString();
    }

    /** El almacén real, con una acción antes de cada escritura para simular fallos o pausas. */
    private final class FaultyStore implements NotificationDispatchStore {
        private final Consumer<DispatchWrite> beforeWrite;

        private FaultyStore(Consumer<DispatchWrite> beforeWrite) {
            this.beforeWrite = beforeWrite;
        }

        @Override
        public Begin begin(UUID scheduleId, int claimedAttemptCount, Instant now) {
            return store.begin(scheduleId, claimedAttemptCount, now);
        }

        @Override
        public WriteResult write(DispatchWrite write) {
            beforeWrite.accept(write);
            return store.write(write);
        }

        @Override
        public List<UUID> findInterrupted(Instant staleBefore, int limit) {
            return store.findInterrupted(staleBefore, limit);
        }

        @Override
        public Recovery recover(UUID scheduleId, Instant staleBefore, Instant now) {
            return store.recover(scheduleId, staleBefore, now);
        }
    }

    private static final class Stub implements NotificationProvider {
        private final NotificationChannel channel;
        private final Supplier<NotificationSendResult> behavior;
        private final AtomicInteger calls = new AtomicInteger();

        private Stub(NotificationChannel channel, Supplier<NotificationSendResult> behavior) {
            this.channel = channel;
            this.behavior = behavior;
        }

        private static Stub email(Supplier<NotificationSendResult> behavior) {
            return new Stub(NotificationChannel.EMAIL, behavior);
        }

        private static NotificationSendResult accepted() {
            return new NotificationSendResult("stub", NotificationChannel.EMAIL, "stub-message",
                    AttemptStatus.ACCEPTED, null, null, null);
        }

        private static NotificationSendResult failure(FailureType failureType) {
            return new NotificationSendResult("stub", NotificationChannel.EMAIL, null,
                    AttemptStatus.FAILED, failureType, "SYNTHETIC", null);
        }

        private int calls() {
            return calls.get();
        }

        @Override
        public boolean qualifiesForOtpDelivery() {
            return true;
        }

        @Override
        public NotificationChannel channel() {
            return channel;
        }

        @Override
        public String providerName() {
            return "stub-" + channel.name().toLowerCase(Locale.ROOT);
        }

        @Override
        public NotificationSendResult send(ChannelMessage message, RecipientSnapshot recipient) {
            calls.incrementAndGet();
            return behavior.get();
        }
    }
}
