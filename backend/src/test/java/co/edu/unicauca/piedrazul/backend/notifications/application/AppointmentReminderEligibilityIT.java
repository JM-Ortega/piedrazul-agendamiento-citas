package co.edu.unicauca.piedrazul.backend.notifications.application;

import co.edu.unicauca.piedrazul.backend.appointment.application.AppointmentEligibilityService;
import co.edu.unicauca.piedrazul.backend.appointment.application.CancelAppointmentUseCaseImpl;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentState;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentTime;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.SchedulingOrigin;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.CancelAppointmentUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentRepository;
import co.edu.unicauca.piedrazul.backend.appointment.eligibility.AppointmentEligibility;
import co.edu.unicauca.piedrazul.backend.appointment.events.AppointmentScheduledEvent;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.mappers.AppointmentMapper;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.persistence.AppointmentJpaRepository;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.persistence.AppointmentRepositoryImpl;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Doctor;
import co.edu.unicauca.piedrazul.backend.doctors.infrastructure.persistence.DoctorRepository;
import co.edu.unicauca.piedrazul.backend.notifications.application.DispatchTestSupport.StubProvider;
import co.edu.unicauca.piedrazul.backend.notifications.application.eventhandler.AppointmentNotificationEventHandler;
import co.edu.unicauca.piedrazul.backend.notifications.application.template.TemplateRenderer;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.*;
import co.edu.unicauca.piedrazul.backend.notifications.domain.policy.ChannelFallbackPolicy;
import co.edu.unicauca.piedrazul.backend.notifications.domain.policy.InterruptedDispatchPolicy.Resolution;
import co.edu.unicauca.piedrazul.backend.notifications.domain.policy.ReminderScheduleCalculator;
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
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.template.InMemoryNotificationTemplateProvider;
import co.edu.unicauca.piedrazul.backend.patients.domain.Patient;
import co.edu.unicauca.piedrazul.backend.patients.domain.Sex;
import co.edu.unicauca.piedrazul.backend.patients.infrastructure.persistence.PatientRepository;
import co.edu.unicauca.piedrazul.backend.shared.enums.SpecialtyCode;
import co.edu.unicauca.piedrazul.backend.support.PostgresIntegrationSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

import static co.edu.unicauca.piedrazul.backend.notifications.application.DispatchTestSupport.outcome;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Elegibilidad de los recordatorios sobre PostgreSQL real, con la persistencia de citas, la lectura
 * y el caso de uso de cancelación verdaderos y transacciones reales. Cada cruce con el despacho se
 * ordena por el punto exacto del commit (tras una escritura del almacén, dentro de la llamada al
 * proveedor o con una transacción que se mantiene abierta) y ninguno depende de esperas por tiempo.
 * Datos exclusivamente sintéticos.
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
        ScheduleClaimService.class,
        AppointmentMapper.class,
        AppointmentEligibilityService.class,
        AppointmentReminderEligibilityIT.TestBeans.class
})
// Sin transacción envolvente del test: aquí se comprueban fronteras reales.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ExtendWith(OutputCaptureExtension.class)
class AppointmentReminderEligibilityIT extends PostgresIntegrationSupport {

    private static final Instant LONG_AGO = Instant.parse("2000-01-01T00:00:00Z");
    private static final int RACE_ROUNDS = 25;

    @TestConfiguration
    static class TestBeans {
        @Bean
        AppointmentRepository appointmentRepository(AppointmentJpaRepository jpaRepository, AppointmentMapper mapper) {
            return new AppointmentRepositoryImpl(jpaRepository, mapper);
        }

        @Bean
        CancelAppointmentUseCase cancelAppointmentUseCase(AppointmentRepository appointmentRepository) {
            return new CancelAppointmentUseCaseImpl(appointmentRepository);
        }
    }

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
    private AppointmentEligibility eligibility;

    @Autowired
    private AppointmentRepository appointmentRepository;

    @Autowired
    private AppointmentJpaRepository appointmentJpaRepository;

    @Autowired
    private CancelAppointmentUseCase cancelAppointment;

    @Autowired
    private PatientRepository patientRepository;

    @Autowired
    private DoctorRepository doctorRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    private final ExecutorService workers = Executors.newCachedThreadPool();
    private final ReadLog readLog = new ReadLog();
    private final StepStore steps = new StepStore();
    private Appointment appointment;

    @BeforeEach
    void isolateFromOtherClasses() {
        // La base se comparte entre clases: nada activo de otras pruebas entra en estos reclamos.
        jdbc.update("UPDATE piedrazul.notification_attempts SET status = 'CANCELLED' WHERE status = 'PROCESSING'");
        jdbc.update("""
                UPDATE piedrazul.notification_schedule SET status = 'CANCELLED', cancelled_at = now()
                WHERE status IN ('PENDING', 'PROCESSING')""");

        appointment = seedAppointment();
    }

    @AfterEach
    void stopWorkers() {
        workers.shutdownNow();
    }

    // ── La lectura, contra la fila confirmada ───────────────────────────

    @ParameterizedTest(name = "{0}")
    @EnumSource(AppointmentState.class)
    void onlyAConfirmedAgendadaIsScheduled(AppointmentState state) {
        setAppointmentState(state);

        assertThat(eligibility.isScheduled(appointment.getIdAppointment()))
                .isEqualTo(state == AppointmentState.AGENDADA);
    }

    @Test
    void missingAppointmentIsNotScheduled() {
        assertThat(eligibility.isScheduled(UUID.randomUUID())).isFalse();
    }

    @Test
    void readIsNotDeceivedByAnEnclosingSnapshot() {
        TransactionTemplate snapshot = new TransactionTemplate(transactionManager);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);

        snapshot.executeWithoutResult(status -> {
            // Fija la instantánea de esta transacción y carga la cita en su contexto de persistencia.
            assertThat(appointmentRepository.findById(appointment.getIdAppointment()).getAppointmentState())
                    .isEqualTo(AppointmentState.AGENDADA);

            // El commit ocurre en otra transacción, posterior a esa instantánea.
            awaitOtherThread(workers.submit(this::cancel));

            assertThat(eligibility.isScheduled(appointment.getIdAppointment())).isFalse();
        });
    }

    // ── Generación ──────────────────────────────────────────────────────

    @Test
    void newAppointmentGeneratesOnlyOneReminderEvenIfTheEventRepeats() {
        LocalDate date = LocalDate.of(2099, 3, 10);
        AppointmentScheduledEvent event = new AppointmentScheduledEvent(UUID.randomUUID(), appointment.getIdPatient(),
                "Paciente Sintético", "+570000000000", "sintetico@example.test", appointment.getIdDoctor(),
                "Dra. Sintética", date, LocalTime.of(8, 0), "FISIOTERAPIA", UUID.randomUUID());
        NotificationSchedulerService scheduler =
                new NotificationSchedulerService(new NotificationFactory(), scheduleRepository);
        NotificationOrchestrator orchestrator = new NotificationOrchestrator(notificationRepository,
                new NotificationFactory(), scheduler, new ChannelFallbackPolicy());
        AppointmentNotificationEventHandler handler = new AppointmentNotificationEventHandler(
                new NotificationModuleApiImpl(orchestrator, null, Clock.systemUTC()),
                new ReminderScheduleCalculator(ZoneId.of("America/Bogota")),
                Clock.systemUTC());

        handler.onAppointmentScheduled(event);
        handler.onAppointmentScheduled(event);

        assertThat(jdbc.queryForList("SELECT type_code FROM piedrazul.notification WHERE aggregate_id = ?",
                String.class, event.appointmentId())).containsExactly("APPOINTMENT_REMINDER_2_DAYS");
        assertThat(jdbc.queryForObject("""
                SELECT s.scheduled_at = TIMESTAMPTZ '2099-03-08 21:00:00+00'
                FROM piedrazul.notification_schedule s
                JOIN piedrazul.notification n ON n.id = s.notification_id
                WHERE n.aggregate_id = ?""", Boolean.class, event.appointmentId())).isTrue();
    }

    // ── Sin cancelación: lo elegible se entrega ─────────────────────────

    @Test
    void scheduledAppointmentReceivesItsReminder() {
        Notification reminder = seedReminder();
        StubProvider email = accepting();

        dispatcher(email).dispatch(claim());

        assertThat(email.calls()).isEqualTo(1);
        assertThat(readLog.results()).containsExactly(true, true);
        assertState(reminder, "ACCEPTED", "SENT");
        assertThat(attemptStatuses(reminder)).containsExactly("ACCEPTED");
    }

    @Test
    void otpDispatchDoesNotReadAppointmentEligibility() {
        Instant now = Instant.now();
        Notification otp = seed(NotificationType.OTP_CODE, AggregateType.VERIFICATION, UUID.randomUUID(),
                Map.of(NotificationVariables.CODE, "000111", "expirationMinutes", "5",
                        "destination", "sintetico@example.test",
                        NotificationVariables.EXPIRES_AT, now.plusSeconds(300).toString()));
        StubProvider email = StubProvider.qualifiedEmail(() -> outcome(DeliveryOutcome.ACCEPTED));

        dispatcher(email).dispatch(claim());

        assertThat(email.calls()).isEqualTo(1);
        assertThat(readLog.results()).isEmpty();
        assertState(otp, "ACCEPTED", "SENT");
    }

    // ── Antes de la entrega ─────────────────────────────────────────────

    @Test
    void cancellationCommittedBeforeTheInitialCheckClosesWithoutAnAttempt() {
        Notification reminder = seedReminder();
        cancel();
        StubProvider email = accepting();

        dispatcher(email).dispatch(claim());

        assertThat(email.calls()).isZero();
        assertThat(readLog.results()).containsExactly(false);
        assertThat(attemptStatuses(reminder)).isEmpty();
        assertCancelled(reminder);
        assertNoReplay(reminder);
    }

    @Test
    void cancellationCommittedBetweenTheInitialAndHandoffChecksPreventsTheHandoff() {
        Notification reminder = seedReminder();
        StubProvider email = accepting();
        // Justo tras confirmar el intento en curso: la primera lectura ya vio la cita agendada.
        steps.afterCommit = write -> {
            if (isProcessingInsert(write)) {
                cancel();
            }
        };

        dispatcher(email).dispatch(claim());

        assertThat(email.calls()).isZero();
        assertThat(readLog.results()).containsExactly(true, false);
        assertThat(attemptStatuses(reminder)).containsExactly("CANCELLED");
        assertThat(jdbc.queryForMap("""
                SELECT failure_type, failed_at, provider_message_id FROM piedrazul.notification_attempts
                WHERE notification_id = ?""", reminder.getId()))
                .containsEntry("failure_type", null)
                .containsEntry("failed_at", null)
                .containsEntry("provider_message_id", null);
        assertCancelled(reminder);
        assertNoReplay(reminder);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = AppointmentState.class, names = {"ATENDIDA", "NO_ASISTIO", "REPROGRAMADA", "CANCELADA"})
    void appointmentNoLongerScheduledIsNotReminded(AppointmentState state) {
        Notification reminder = seedReminder();
        setAppointmentState(state);
        StubProvider email = accepting();

        dispatcher(email).dispatch(claim());

        assertThat(email.calls()).isZero();
        assertThat(attemptStatuses(reminder)).isEmpty();
        assertCancelled(reminder);
    }

    @Test
    void missingAppointmentFailsClosed() {
        Notification reminder = seed(NotificationType.APPOINTMENT_REMINDER_2_DAYS, AggregateType.APPOINTMENT,
                UUID.randomUUID(), reminderVariables());
        StubProvider email = accepting();

        dispatcher(email).dispatch(claim());

        assertThat(email.calls()).isZero();
        assertThat(attemptStatuses(reminder)).isEmpty();
        assertCancelled(reminder);
    }

    // ── Cancelación aún sin confirmar ───────────────────────────────────

    @Test
    void uncommittedCancellationDoesNotBlockNorPreventTheHandoff() {
        Notification reminder = seedReminder();
        OpenCancellation open = OpenCancellation.start(this);
        await(open.written);

        // Fila ya modificada y bloqueada por la otra transacción, pero sin confirmar.
        assertThat(appointmentRow()).isEqualTo("AGENDADA");
        assertThat(uncommittedAppointmentRowIsLocked()).isTrue();

        StubProvider email = accepting();
        email.onSend = () -> {
            assertThat(appointmentRow()).isEqualTo("AGENDADA");
            open.commitAndWait();
        };

        dispatcher(email).dispatch(claim());

        assertThat(appointmentRow()).isEqualTo("CANCELADA");
        assertThat(email.calls()).isEqualTo(1);
        assertThat(readLog.results()).containsExactly(true, true);
        assertState(reminder, "ACCEPTED", "SENT");
        assertThat(attemptStatuses(reminder)).containsExactly("ACCEPTED");
        assertNoReplay(reminder);
    }

    @Test
    void rolledBackCancellationNeverStopsTheReminder() {
        Notification reminder = seedReminder();
        OpenCancellation open = OpenCancellation.start(this);
        await(open.written);
        open.rollbackAndWait();
        StubProvider email = accepting();

        dispatcher(email).dispatch(claim());

        assertThat(email.calls()).isEqualTo(1);
        assertState(reminder, "ACCEPTED", "SENT");
    }

    // ── Durante la entrega ──────────────────────────────────────────────

    @Test
    void cancellationDuringAnAcceptedHandoffKeepsTheAcceptance() {
        Notification reminder = seedReminder();
        StubProvider email = accepting();
        email.onSend = this::cancel;

        dispatcher(email).dispatch(claim());

        assertThat(appointmentRow()).isEqualTo("CANCELADA");
        assertThat(email.calls()).isEqualTo(1);
        assertState(reminder, "ACCEPTED", "SENT");
        assertThat(attemptStatuses(reminder)).containsExactly("ACCEPTED");
        assertNoReplay(reminder);
    }

    @Test
    void cancellationDuringAnUncertainHandoffStaysUncertain() {
        Notification reminder = seedReminder();
        StubProvider email = StubProvider.email(() -> outcome(DeliveryOutcome.UNKNOWN));
        email.onSend = this::cancel;

        dispatcher(email).dispatch(claim());

        assertThat(email.calls()).isEqualTo(1);
        assertUnknownTerminal(reminder);
        assertNoReplay(reminder);
    }

    @Test
    void noDatabaseLockIsHeldAcrossTheProviderCall() {
        Notification reminder = seedReminder();
        StubProvider email = accepting();
        email.onSend = () -> assertThat(jdbc.queryForList("""
                SELECT a.id FROM piedrazul.appointment a, piedrazul.notification n, piedrazul.notification_schedule s
                WHERE a.id = ? AND n.id = ? AND s.notification_id = n.id
                FOR UPDATE NOWAIT""", UUID.class, appointment.getIdAppointment(), reminder.getId())).hasSize(1);

        dispatcher(email).dispatch(claim());

        assertThat(email.calls()).isEqualTo(1);
        assertState(reminder, "ACCEPTED", "SENT");
    }

    @Test
    void racingCancellationNeverLosesToAReadThatStartedAfterItsCommit() {
        for (int round = 0; round < RACE_ROUNDS; round++) {
            // Solo reparte en qué orden caen commit y lectura; lo comprobado vale para cualquiera.
            long stagger = TimeUnit.MILLISECONDS.toNanos(round % 8);
            Notification reminder = seedReminder();
            CyclicBarrier start = new CyclicBarrier(2);
            AtomicBoolean committed = new AtomicBoolean();
            AtomicBoolean committedBeforeHandoffRead = new AtomicBoolean();
            ReadLog roundLog = new ReadLog();
            roundLog.beforeRead = read -> {
                if (read == 2) {
                    committedBeforeHandoffRead.set(committed.get());
                }
            };
            StepStore roundSteps = new StepStore();
            Future<?> cancellation = workers.submit(() -> {
                await(start);
                cancel();
                committed.set(true);
            });
            roundSteps.afterCommit = write -> {
                if (isProcessingInsert(write)) {
                    await(start);
                    LockSupport.parkNanos(stagger);
                }
            };
            StubProvider email = accepting();

            dispatcher(roundSteps, roundLog, email).dispatch(claim());
            awaitOtherThread(cancellation);

            if (committedBeforeHandoffRead.get()) {
                assertThat(email.calls()).isZero();
            }

            if (email.calls() == 0) {
                assertThat(attemptStatuses(reminder)).containsExactly("CANCELLED");
                assertCancelled(reminder);
            } else {
                assertThat(committedBeforeHandoffRead).isFalse();
                assertThat(attemptStatuses(reminder)).containsExactly("ACCEPTED");
                assertState(reminder, "ACCEPTED", "SENT");
            }

            appointment = seedAppointment();
        }
    }

    // ── Reintentos, otro canal y recuperación ───────────────────────────

    @Test
    void cancellationBeforeARetryKeepsTheEarlierAttemptAndAddsNoHandoff() {
        Notification reminder = seedReminder();
        StubProvider email = StubProvider.email(() -> outcome(DeliveryOutcome.RETRYABLE));
        NotificationDispatcherService dispatcher = dispatcher(email);

        dispatcher.dispatch(claim());
        assertState(reminder, "PROCESSING", "PENDING");

        cancel();
        makeRetryDue(reminder);
        dispatcher.dispatch(claim());

        assertThat(email.calls()).isEqualTo(1);
        assertThat(attemptStatuses(reminder)).containsExactly("FAILED");
        assertCancelled(reminder);
        assertNoReplay(reminder);
    }

    @Test
    void cancellationAtTheRetryHandoffCancelsOnlyThatAttempt() {
        Notification reminder = seedReminder();
        StubProvider email = StubProvider.email(() -> outcome(DeliveryOutcome.RETRYABLE));
        NotificationDispatcherService dispatcher = dispatcher(email);
        dispatcher.dispatch(claim());
        makeRetryDue(reminder);

        steps.afterCommit = write -> {
            if (isProcessingInsert(write)) {
                cancel();
            }
        };
        dispatcher.dispatch(claim());

        assertThat(email.calls()).isEqualTo(1);
        assertThat(attemptStatuses(reminder)).containsExactly("FAILED", "CANCELLED");
        assertCancelled(reminder);
        assertNoReplay(reminder);
    }

    @Test
    void cancellationBetweenChannelsStopsTheFallback() {
        Notification reminder = seedReminder(NotificationChannel.SMS, NotificationChannel.EMAIL);
        StubProvider sms = new StubProvider(NotificationChannel.SMS, false)
                .then(() -> outcome(DeliveryOutcome.REJECTED));
        StubProvider email = accepting();
        // Con el rechazo del primer canal ya confirmado y el segundo aún sin abrir.
        steps.afterCommit = write -> {
            if (write.resolvedAttempt() != null) {
                cancel();
            }
        };

        dispatcher(sms, email).dispatch(claim());

        assertThat(sms.calls()).isEqualTo(1);
        assertThat(email.calls()).isZero();
        assertThat(attemptStatuses(reminder)).containsExactly("FAILED");
        assertCancelled(reminder);
        assertNoReplay(reminder);
    }

    @Test
    void cancellationAtTheFallbackHandoffCancelsOnlyThatAttempt() {
        Notification reminder = seedReminder(NotificationChannel.SMS, NotificationChannel.EMAIL);
        StubProvider sms = new StubProvider(NotificationChannel.SMS, false)
                .then(() -> outcome(DeliveryOutcome.REJECTED));
        StubProvider email = accepting();
        steps.afterCommit = write -> {
            if (isProcessingInsert(write) && write.newAttempt().getChannel() == NotificationChannel.EMAIL) {
                cancel();
            }
        };

        dispatcher(sms, email).dispatch(claim());

        assertThat(sms.calls()).isEqualTo(1);
        assertThat(email.calls()).isZero();
        assertThat(attemptStatuses(reminder)).containsExactly("FAILED", "CANCELLED");
        assertCancelled(reminder);
    }

    @Test
    void requeuedWorkFollowedByCancellationIsClosedByTheDispatcher() {
        Notification reminder = seedReminder();
        ClaimedSchedule interrupted = claim();

        assertThat(recover(interrupted).resolution()).isEqualTo(Resolution.REQUEUED);
        assertState(reminder, "PROCESSING", "PENDING");

        cancel();
        StubProvider email = accepting();
        dispatcher(email).dispatch(claim());

        assertThat(email.calls()).isZero();
        assertThat(attemptStatuses(reminder)).isEmpty();
        assertCancelled(reminder);
        assertNoReplay(reminder);
    }

    @Test
    void requeuedWorkStillEligibleIsDelivered() {
        Notification reminder = seedReminder();
        assertThat(recover(claim()).resolution()).isEqualTo(Resolution.REQUEUED);
        StubProvider email = accepting();

        dispatcher(email).dispatch(claim());

        assertThat(email.calls()).isEqualTo(1);
        assertThat(readLog.results()).containsExactly(true, true);
        assertState(reminder, "ACCEPTED", "SENT");
    }

    // ── Trabajo que pudo llegar al proveedor ────────────────────────────

    @Test
    void staleInFlightWorkAfterCancellationStaysUncertain() {
        Notification reminder = seedReminder();
        ClaimedSchedule claim = claim();
        inFlightAttempt(reminder, claim);
        cancel();

        assertThat(recover(claim).resolution()).isEqualTo(Resolution.ACCEPTANCE_UNKNOWN);

        assertUnknownTerminal(reminder);
        assertNoReplay(reminder);
    }

    @Test
    void acceptedWorkFoundAfterCancellationKeepsItsAcceptance() {
        Notification reminder = seedReminder();
        ClaimedSchedule claim = claim();
        NotificationAttempt attempt = inFlightAttempt(reminder, claim);
        attempt.markAccepted("stub-message", Instant.now());
        attemptRepository.save(attempt);
        cancel();
        StubProvider email = accepting();

        dispatcher(email).dispatch(new ClaimedSchedule(claim.scheduleId(), 1));

        assertThat(email.calls()).isZero();
        assertThat(readLog.results()).isEmpty();
        assertState(reminder, "ACCEPTED", "SENT");
        assertThat(attemptStatuses(reminder)).containsExactly("ACCEPTED");
        assertNoReplay(reminder);
    }

    @Test
    void uncertainWorkFoundAfterCancellationStaysUncertain() {
        Notification reminder = seedReminder();
        ClaimedSchedule claim = claim();
        NotificationAttempt attempt = inFlightAttempt(reminder, claim);
        attempt.markAcceptanceUnknown("ACCEPTANCE_UNKNOWN", "sintético", Instant.now());
        attemptRepository.save(attempt);
        cancel();
        StubProvider email = accepting();

        dispatcher(email).dispatch(new ClaimedSchedule(claim.scheduleId(), 1));

        assertThat(email.calls()).isZero();
        assertThat(readLog.results()).isEmpty();
        assertUnknownTerminal(reminder);
        assertNoReplay(reminder);
    }

    @Test
    void inFlightWorkClaimedAgainAfterCancellationIsNeverCancelledNorResent() {
        Notification reminder = seedReminder();
        ClaimedSchedule claim = claim();
        inFlightAttempt(reminder, claim);
        cancel();
        StubProvider email = accepting();

        dispatcher(email).dispatch(new ClaimedSchedule(claim.scheduleId(), 1));

        assertThat(email.calls()).isZero();
        assertThat(readLog.results()).isEmpty();
        assertUnknownTerminal(reminder);
    }

    // ── Trabajador tardío ───────────────────────────────────────────────

    @Test
    void lateWorkerFencedBeforeTheHandoffNeitherInvokesNorOverwrites() {
        Notification reminder = seedReminder();
        ClaimedSchedule claim = claim();
        StubProvider email = accepting();
        steps.afterCommit = write -> {
            if (isProcessingInsert(write)) {
                assertThat(recover(claim).resolution()).isEqualTo(Resolution.ACCEPTANCE_UNKNOWN);
                cancel();
            }
        };

        dispatcher(email).dispatch(claim);

        assertThat(email.calls()).isZero();
        assertThat(readLog.results()).containsExactly(true, false);
        assertUnknownTerminal(reminder);
        assertNoReplay(reminder);
    }

    @Test
    void lateAcceptanceAfterRecoveryAndCancellationChangesNothing(CapturedOutput output) {
        Notification reminder = seedReminder();
        ClaimedSchedule claim = claim();
        StubProvider email = accepting();
        email.onSend = () -> {
            assertThat(recover(claim).resolution()).isEqualTo(Resolution.ACCEPTANCE_UNKNOWN);
            cancel();
        };

        dispatcher(email).dispatch(claim);

        assertThat(email.calls()).isEqualTo(1);
        assertUnknownTerminal(reminder);
        assertThat(output).contains("NOTIFICATION_LATE_ACCEPTANCE");
        assertNoReplay(reminder);
    }

    // ── Fallos ──────────────────────────────────────────────────────────

    @Test
    void failedInitialReadClosesWithoutProviderNorAttempt() {
        Notification reminder = seedReminder();
        readLog.failingRead = 1;
        StubProvider email = accepting();

        dispatcher(email).dispatch(claim());

        assertThat(email.calls()).isZero();
        assertThat(attemptStatuses(reminder)).isEmpty();
        assertState(reminder, "FAILED", "FAILED");
        assertNoReplay(reminder);
    }

    @Test
    void failedHandoffReadInvokesNoProviderAndIsNotUncertain() {
        Notification reminder = seedReminder();
        readLog.failingRead = 2;
        StubProvider email = accepting();

        dispatcher(email).dispatch(claim());

        assertThat(email.calls()).isZero();
        assertThat(jdbc.queryForMap("""
                SELECT status, failure_type, error_code FROM piedrazul.notification_attempts
                WHERE notification_id = ?""", reminder.getId()))
                .containsEntry("status", "FAILED")
                .containsEntry("failure_type", "PERMANENT")
                .containsEntry("error_code", "DISPATCH_INTERRUPTED");
        assertState(reminder, "FAILED", "FAILED");
        assertNoReplay(reminder);
    }

    /** Riesgo aceptado: sobrestima la incertidumbre (no hubo proveedor) pero nunca reenvía. */
    @Test
    void failedHandoffReadPlusFailedTerminalWriteEndsConservativelyUnknown() {
        Notification reminder = seedReminder();
        ClaimedSchedule claim = claim();
        readLog.failingRead = 2;
        StubProvider email = accepting();
        steps.beforeWrite = write -> {
            if (write.finish() == DispatchFinish.FAILED) {
                throw new IllegalStateException("base sintética caída");
            }
        };

        dispatcher(email).dispatch(claim);

        assertThat(email.calls()).isZero();
        assertThat(attemptStatuses(reminder)).containsExactly("PROCESSING");

        assertThat(recover(claim).resolution()).isEqualTo(Resolution.ACCEPTANCE_UNKNOWN);

        assertThat(email.calls()).isZero();
        assertUnknownTerminal(reminder);
        assertNoReplay(reminder);
    }

    @Test
    void rolledBackCancellationWithoutAttemptIsRequeuedAndClosedLater() {
        Notification reminder = seedReminder();
        cancel();
        ClaimedSchedule claim = claim();
        StubProvider email = accepting();

        withFailingUpdate("notification_schedule", "NEW.status = 'CANCELLED'",
                () -> dispatcher(email).dispatch(claim));

        // Ambas escrituras se revirtieron enteras: sigue reclamado, sin nada que resolver.
        assertState(reminder, "PROCESSING", "PROCESSING");
        assertThat(recover(claim).resolution()).isEqualTo(Resolution.REQUEUED);

        dispatcher(email).dispatch(claim());

        assertThat(email.calls()).isZero();
        assertCancelled(reminder);
    }

    /** El mismo riesgo aceptado por la otra vía: el cierre no se pudo escribir con un intento abierto. */
    @Test
    void rolledBackCancellationOfAnOpenAttemptEndsConservativelyUnknown() {
        Notification reminder = seedReminder();
        ClaimedSchedule claim = claim();
        StubProvider email = accepting();
        steps.afterCommit = write -> {
            if (isProcessingInsert(write)) {
                cancel();
            }
        };

        withFailingUpdate("notification_schedule", "NEW.status = 'CANCELLED'",
                () -> dispatcher(email).dispatch(claim));

        assertThat(email.calls()).isZero();
        assertThat(readLog.results()).containsExactly(true, false);
        assertThat(attemptStatuses(reminder)).containsExactly("PROCESSING");

        assertThat(recover(claim).resolution()).isEqualTo(Resolution.ACCEPTANCE_UNKNOWN);

        assertThat(email.calls()).isZero();
        assertUnknownTerminal(reminder);
        assertNoReplay(reminder);
    }

    @Test
    void transientCancellationWriteFailureStillEndsAsCancelled() {
        Notification reminder = seedReminder();
        StubProvider email = accepting();
        AtomicInteger failures = new AtomicInteger();
        steps.afterCommit = write -> {
            if (isProcessingInsert(write)) {
                cancel();
            }
        };
        steps.beforeWrite = write -> {
            if (write.finish() == DispatchFinish.CANCELLED && failures.getAndIncrement() == 0) {
                throw new IllegalStateException("base sintética caída");
            }
        };

        dispatcher(email).dispatch(claim());

        assertThat(email.calls()).isZero();
        assertThat(attemptStatuses(reminder)).containsExactly("CANCELLED");
        assertCancelled(reminder);
    }

    // ── Confirmaciones históricas ───────────────────────────────────────

    @Test
    void queuedConfirmationLoadsAndClosesWithoutProvider() {
        Notification confirmation = seedConfirmation();

        assertThat(notificationRepository.findById(confirmation.getId()))
                .get().extracting(Notification::getType).isEqualTo(NotificationType.APPOINTMENT_SCHEDULED);

        StubProvider email = accepting();
        dispatcher(email).dispatch(claim());

        assertThat(email.calls()).isZero();
        assertThat(readLog.results()).isEmpty();
        assertThat(attemptStatuses(confirmation)).isEmpty();
        assertCancelled(confirmation);
        assertNoReplay(confirmation);
    }

    @Test
    void confirmationAcceptedBeforeTheChangeKeepsItsHistory() {
        Notification confirmation = seedConfirmation();
        ClaimedSchedule claim = claim();
        NotificationAttempt attempt = inFlightAttempt(confirmation, claim);
        attempt.markAccepted("stub-message", Instant.now());
        attemptRepository.save(attempt);
        StubProvider email = accepting();

        dispatcher(email).dispatch(new ClaimedSchedule(claim.scheduleId(), 1));

        assertThat(email.calls()).isZero();
        assertState(confirmation, "ACCEPTED", "SENT");
        assertThat(attemptStatuses(confirmation)).containsExactly("ACCEPTED");
    }

    @Test
    void interruptedConfirmationStaysUncertain() {
        Notification confirmation = seedConfirmation();
        ClaimedSchedule claim = claim();
        inFlightAttempt(confirmation, claim);

        assertThat(recover(claim).resolution()).isEqualTo(Resolution.ACCEPTANCE_UNKNOWN);

        assertUnknownTerminal(confirmation);
        assertNoReplay(confirmation);
    }

    // ── Soporte ─────────────────────────────────────────────────────────

    private void cancel() {
        cancelAppointment.cancel(appointment.getIdAppointment(), appointment.getIdPatient());
    }

    private Appointment seedAppointment() {
        UUID patient = seedPerson();
        patientRepository.save(new Patient(patient, Sex.FEMENINO, LocalDate.of(1990, 1, 1), null));
        UUID doctor = seedPerson();
        doctorRepository.save(new Doctor(doctor, LocalDate.now(), LocalDate.now().plusYears(1), 4, true, 30));

        return appointmentRepository.save(Appointment.reconstruct(null, doctor, patient, SpecialtyCode.FISIOTERAPIA,
                AppointmentState.AGENDADA, LocalDate.now().plusDays(30),
                AppointmentTime.withoutBusinessHoursRestriction(LocalTime.of(8, 0)), SchedulingOrigin.MANUAL));
    }

    private UUID seedPerson() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO piedrazul.person (id, identification_type, identification, first_name, last_name, phone, email)
                VALUES (?, 'CEDULA', ?, 'Sintético', 'Prueba', '+570000000000', 'sintetico@example.test')""",
                id, "rem-" + id);
        return id;
    }

    private void setAppointmentState(AppointmentState state) {
        jdbc.update("UPDATE piedrazul.appointment SET state_code = ? WHERE id = ?",
                state.name(), appointment.getIdAppointment());
    }

    private String appointmentRow() {
        return jdbc.queryForObject("SELECT state_code FROM piedrazul.appointment WHERE id = ?",
                String.class, appointment.getIdAppointment());
    }

    /** Hay una escritura sin confirmar: otra sesión no puede tomar la fila. */
    private boolean uncommittedAppointmentRowIsLocked() {
        try {
            jdbc.queryForList("SELECT id FROM piedrazul.appointment WHERE id = ? FOR UPDATE NOWAIT",
                    UUID.class, appointment.getIdAppointment());
            return false;
        } catch (org.springframework.dao.DataAccessException exception) {
            return true;
        }
    }

    private Map<String, String> reminderVariables() {
        return Map.of("patientName", "Paciente Sintético", "doctorName", "Dra. Sintética",
                "date", "2026-01-10", "time", "08:00");
    }

    private Notification seedReminder(NotificationChannel... channels) {
        return seed(NotificationType.APPOINTMENT_REMINDER_2_DAYS, AggregateType.APPOINTMENT,
                appointment.getIdAppointment(), reminderVariables(), channels);
    }

    private Notification seedConfirmation() {
        return seed(NotificationType.APPOINTMENT_SCHEDULED, AggregateType.APPOINTMENT,
                appointment.getIdAppointment(), reminderVariables());
    }

    private Notification seed(
            NotificationType type,
            AggregateType aggregateType,
            UUID aggregateId,
            Map<String, String> variables,
            NotificationChannel... channels
    ) {
        Instant now = Instant.now();
        List<NotificationChannel> order = channels.length == 0 ? List.of(NotificationChannel.EMAIL) : List.of(channels);
        Notification notification = Notification.create(
                type,
                new AggregateReference(aggregateType, aggregateId),
                new RecipientSnapshot(appointment.getIdPatient(), "Sintético", "+570000000000",
                        "sintetico@example.test", Locale.forLanguageTag("es-CO"), null),
                new ChannelPreference(order),
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

    private NotificationAttempt inFlightAttempt(Notification notification, ClaimedSchedule claim) {
        store.begin(claim.scheduleId(), claim.attemptCount(), Instant.now());
        NotificationAttempt attempt = NotificationAttempt.create(notification.getId(), claim.scheduleId(),
                NotificationChannel.EMAIL, "stub-email", 1, Instant.now());
        attempt.markProcessing(Instant.now());
        assertThat(store.write(DispatchWrite.insert(claim.scheduleId(), claim.attemptCount(), attempt,
                DispatchFinish.CONTINUE, Instant.now()))).isEqualTo(NotificationDispatchStore.WriteResult.APPLIED);
        return attempt;
    }

    /** Cuenta como antiguo todo lo que existe: cada prueba decide qué trabajo se interrumpió. */
    private Recovery recover(ClaimedSchedule claim) {
        Instant now = Instant.now();
        return store.recover(claim.scheduleId(), now.plusSeconds(60), now);
    }

    private void makeRetryDue(Notification notification) {
        jdbc.update("UPDATE piedrazul.notification_schedule SET next_retry_at = ? WHERE notification_id = ?",
                java.sql.Timestamp.from(LONG_AGO), notification.getId());
    }

    /** Hace fallar de verdad, dentro de PostgreSQL, un UPDATE que cumpla la condición. */
    private void withFailingUpdate(String table, String condition, Runnable action) {
        jdbc.execute("""
                CREATE FUNCTION piedrazul.reminder_synthetic_failure() RETURNS trigger AS $$
                BEGIN RAISE EXCEPTION 'fallo sintético'; END $$ LANGUAGE plpgsql""");
        jdbc.execute("CREATE TRIGGER reminder_synthetic_failure BEFORE UPDATE ON piedrazul." + table
                + " FOR EACH ROW WHEN (" + condition + ") EXECUTE FUNCTION piedrazul.reminder_synthetic_failure()");

        try {
            action.run();
        } finally {
            jdbc.execute("DROP TRIGGER reminder_synthetic_failure ON piedrazul." + table);
            jdbc.execute("DROP FUNCTION piedrazul.reminder_synthetic_failure()");
        }
    }

    private static boolean isProcessingInsert(DispatchWrite write) {
        return write.newAttempt() != null && write.newAttempt().getStatus() == AttemptStatus.PROCESSING;
    }

    private static StubProvider accepting() {
        return StubProvider.email(() -> outcome(DeliveryOutcome.ACCEPTED));
    }

    private NotificationDispatcherService dispatcher(NotificationProvider... providers) {
        return dispatcher(steps, readLog, providers);
    }

    private NotificationDispatcherService dispatcher(
            NotificationDispatchStore dispatchStore,
            AppointmentEligibility appointmentEligibility,
            NotificationProvider... providers
    ) {
        return new NotificationDispatcherService(
                dispatchStore,
                new ProviderRegistry(List.of(providers)),
                new TemplateRenderer(new InMemoryNotificationTemplateProvider()),
                new RetryPolicy(),
                new ChannelFallbackPolicy(),
                new RecipientEmailPolicy(DispatchTestSupport.VALIDATOR),
                appointmentEligibility,
                false,
                Clock.fixed(Instant.now(), ZoneOffset.UTC)
        );
    }

    private void assertState(Notification notification, String notificationStatus, String scheduleStatus) {
        assertThat(jdbc.queryForObject("SELECT status FROM piedrazul.notification WHERE id = ?",
                String.class, notification.getId())).isEqualTo(notificationStatus);
        assertThat(jdbc.queryForObject("SELECT status FROM piedrazul.notification_schedule WHERE notification_id = ?",
                String.class, notification.getId())).isEqualTo(scheduleStatus);
    }

    private void assertCancelled(Notification notification) {
        assertState(notification, "CANCELLED", "CANCELLED");
        assertThat(jdbc.queryForObject("SELECT cancelled_at FROM piedrazul.notification_schedule WHERE notification_id = ?",
                Instant.class, notification.getId())).isNotNull();
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
    }

    /** Ni una pasada de reclamo ni una de recuperación vuelven a tocar el trabajo cerrado. */
    private void assertNoReplay(Notification notification) {
        String closed = snapshot(notification);

        assertThat(claimService.claimDueSchedules(Instant.now(), 10)).isEmpty();
        assertThat(store.findInterrupted(Instant.now().plusSeconds(60), 500)).isEmpty();
        assertThat(snapshot(notification)).isEqualTo(closed);
    }

    private List<String> attemptStatuses(Notification notification) {
        return jdbc.queryForList("SELECT status FROM piedrazul.notification_attempts WHERE notification_id = ? ORDER BY created_at, channel DESC",
                String.class, notification.getId());
    }

    private String snapshot(Notification notification) {
        return jdbc.queryForList("""
                SELECT n.status, n.updated_at, s.status, s.updated_at, s.next_retry_at, s.cancelled_at, a.status, a.updated_at
                FROM piedrazul.notification n
                JOIN piedrazul.notification_schedule s ON s.notification_id = n.id
                LEFT JOIN piedrazul.notification_attempts a ON a.notification_id = n.id
                WHERE n.id = ? ORDER BY a.created_at""", notification.getId()).toString();
    }

    private static void awaitOtherThread(Future<?> task) {
        try {
            task.get(30, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException exception) {
            throw new IllegalStateException(exception);
        }
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

    /** La lectura real, con su historial y una lectura que puede fallar. */
    private final class ReadLog implements AppointmentEligibility {
        private final List<Boolean> results = new CopyOnWriteArrayList<>();
        private final AtomicInteger reads = new AtomicInteger();
        private Consumer<Integer> beforeRead = read -> {
        };
        private int failingRead;

        @Override
        public boolean isScheduled(UUID appointmentId) {
            int read = reads.incrementAndGet();
            beforeRead.accept(read);

            if (read == failingRead) {
                throw new IllegalStateException("lectura sintética fallida");
            }

            boolean scheduled = eligibility.isScheduled(appointmentId);
            results.add(scheduled);
            return scheduled;
        }

        private List<Boolean> results() {
            return results;
        }
    }

    /** El almacén real, con acciones justo antes de una escritura y justo después de su commit. */
    private final class StepStore implements NotificationDispatchStore {
        private Consumer<DispatchWrite> beforeWrite = write -> {
        };
        private Consumer<DispatchWrite> afterCommit = write -> {
        };

        @Override
        public Begin begin(UUID scheduleId, int claimedAttemptCount, Instant now) {
            return store.begin(scheduleId, claimedAttemptCount, now);
        }

        @Override
        public WriteResult write(DispatchWrite write) {
            beforeWrite.accept(write);
            WriteResult result = store.write(write);
            afterCommit.accept(write);
            return result;
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

    /** Cancelación real dentro de una transacción que la prueba mantiene abierta. */
    private static final class OpenCancellation {
        private final CountDownLatch written = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicBoolean rollback = new AtomicBoolean();
        private Future<?> finished;

        private static OpenCancellation start(AppointmentReminderEligibilityIT test) {
            OpenCancellation open = new OpenCancellation();
            open.finished = test.workers.submit(() ->
                    new TransactionTemplate(test.transactionManager).executeWithoutResult(status -> {
                        test.cancel();
                        test.appointmentJpaRepository.flush();
                        open.written.countDown();
                        await(open.release);

                        if (open.rollback.get()) {
                            status.setRollbackOnly();
                        }
                    }));
            return open;
        }

        private void commitAndWait() {
            release.countDown();
            awaitOtherThread(finished);
        }

        private void rollbackAndWait() {
            rollback.set(true);
            commitAndWait();
        }
    }
}
