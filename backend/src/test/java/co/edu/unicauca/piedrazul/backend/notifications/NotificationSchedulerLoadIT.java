package co.edu.unicauca.piedrazul.backend.notifications;

import co.edu.unicauca.piedrazul.backend.notifications.application.NotificationDispatcherService;
import co.edu.unicauca.piedrazul.backend.notifications.application.ProviderRegistry;
import co.edu.unicauca.piedrazul.backend.notifications.application.RecipientEmailPolicy;
import co.edu.unicauca.piedrazul.backend.notifications.application.template.TemplateRenderer;
import co.edu.unicauca.piedrazul.backend.notifications.domain.message.ChannelMessage;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.*;
import co.edu.unicauca.piedrazul.backend.notifications.domain.policy.ChannelFallbackPolicy;
import co.edu.unicauca.piedrazul.backend.notifications.domain.policy.RetryPolicy;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.*;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.mappers.NotificationAttemptMapper;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.mappers.NotificationMapper;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.mappers.NotificationScheduleMapper;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.persistence.adapter.NotificationAttemptRepositoryAdapter;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.persistence.adapter.NotificationDispatchStoreAdapter;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.persistence.adapter.NotificationRepositoryAdapter;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.persistence.adapter.NotificationScheduleRepositoryAdapter;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.scheduler.DueNotificationJob;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.scheduler.ScheduleClaimService;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.scheduler.StaleNotificationAttemptJob;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.template.InMemoryNotificationTemplateProvider;
import co.edu.unicauca.piedrazul.backend.support.PostgresIntegrationSupport;
import jakarta.validation.Validation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Escenario representativo de 50 notificaciones con los jobs reales sobre un programador real, para
 * medir cuánto se interfieren entre sí. El programador de un hilo es el de producción: la aplicación
 * no configura {@code spring.task.scheduling.pool.size} y el valor por defecto es 1. El de dos hilos
 * es solo el control. Los retardos del proveedor son sintéticos, y los intervalos de los jobs están
 * reducidos para que la prueba dure segundos: lo que se interpreta es la interferencia, no los
 * tiempos absolutos. Las mediciones quedan en {@value #EVIDENCE}.
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
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class NotificationSchedulerLoadIT extends PostgresIntegrationSupport {

    private static final String EVIDENCE = "target/w1b-scheduler-evidence.txt";
    private static final Instant LONG_AGO = Instant.parse("2000-01-01T00:00:00Z");
    private static final int BATCH = 50;
    private static final int CRASHED_CLAIMS = 5;
    private static final Duration THRESHOLD = Duration.ofHours(2);
    private static final Duration HUNG_CALL = Duration.ofSeconds(2);

    @Autowired
    private NotificationDispatchStore store;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private NotificationScheduleRepository scheduleRepository;

    @Autowired
    private ScheduleClaimService claimService;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID personId;

    @BeforeEach
    void seedPerson() {
        personId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO piedrazul.person (id, identification_type, identification, first_name, last_name, phone, email)
                VALUES (?, 'CEDULA', ?, 'Sintético', 'Prueba', '+570000000000', 'sintetico@example.test')""",
                personId, "w1b-load-" + personId);
    }

    @Test
    void fiftyNotificationsResolveWithoutDuplicatesAndTheInterferenceIsMeasured() throws Exception {
        List<String> evidence = new ArrayList<>();
        evidence.add("Mediciones sobre PostgreSQL 16 real. Proveedor sintético; jobs reales: despacho cada 100 ms,");
        evidence.add("recuperación cada 150 ms, y un job ajeno de 50 ms que representa al resto de @Scheduled.");

        Measure fast = run("normal, 1 hilo (producción)", 1, new Profile(50, false));
        Measure faulty = run("lento y con fallos, 1 hilo (producción)", 1, new Profile(100, true));
        Measure control = run("lento y con fallos, 2 hilos (control)", 2, new Profile(100, true));

        evidence.add(fast.toString());
        evidence.add(faulty.toString());
        evidence.add(control.toString());
        evidence.add(estimates(fast));

        Files.createDirectories(Path.of(EVIDENCE).getParent());
        Files.write(Path.of(EVIDENCE), evidence);
        evidence.forEach(System.out::println);

        // Con un solo hilo todo @Scheduled espera a la llamada colgada; con dos, no.
        assertThat(faulty.foreignJobMaxGapMs).isGreaterThanOrEqualTo(HUNG_CALL.toMillis());
        assertThat(control.foreignJobMaxGapMs).isLessThan(HUNG_CALL.toMillis());
    }

    /** Nada activo de otras pruebas ni de la medición anterior entra en estos reclamos. */
    private void closeActiveWork() {
        jdbc.update("UPDATE piedrazul.notification_attempts SET status = 'CANCELLED' WHERE status = 'PROCESSING'");
        jdbc.update("""
                UPDATE piedrazul.notification_schedule SET status = 'CANCELLED', cancelled_at = now()
                WHERE status IN ('PENDING', 'PROCESSING')""");
    }

    private Measure run(String name, int threads, Profile profile) throws Exception {
        closeActiveWork();

        // Primero: cada reclamo debe tomar justo el schedule que se acaba de crear.
        List<UUID> crashed = crashClaims();
        List<UUID> batch = new ArrayList<>();

        for (int index = 0; index < BATCH; index++) {
            batch.add(seed(index % 5 == 0).getId());
        }

        SyntheticProvider provider = new SyntheticProvider(profile);
        DueNotificationJob dueJob = new DueNotificationJob(claimService, dispatcher(provider), Clock.systemUTC());
        StaleNotificationAttemptJob staleJob =
                new StaleNotificationAttemptJob(store, Clock.systemUTC(), true, THRESHOLD);

        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(threads);
        scheduler.setThreadNamePrefix("w1b-load-");
        scheduler.initialize();

        long start = System.nanoTime();
        Gaps dueRuns = new Gaps(start);
        Gaps recoveryRuns = new Gaps(start);
        Gaps foreignRuns = new Gaps(start);

        try {
            scheduler.scheduleWithFixedDelay(dueRuns.around(dueJob::processDueNotifications), Duration.ofMillis(100));
            scheduler.scheduleWithFixedDelay(recoveryRuns.around(staleJob::processStaleAttempts), Duration.ofMillis(150));
            scheduler.scheduleWithFixedDelay(foreignRuns.around(() -> {
            }), Duration.ofMillis(50));

            // Un código que llega con el lote ya en marcha.
            provider.awaitFirstCall();
            Notification lateOtp = seed(true);
            long lateOtpQueuedAt = System.nanoTime();

            awaitSettled(batch, crashed, lateOtp.getId());

            long elapsed = System.nanoTime() - start;

            assertOutcomes(batch, crashed, lateOtp.getId(), provider, profile);

            return new Measure(
                    name,
                    Duration.ofNanos(elapsed).toMillis(),
                    provider.simulatedMs.get(),
                    provider.calls.get(),
                    dueRuns.maxGapMs(),
                    recoveryRuns.maxGapMs(),
                    foreignRuns.maxGapMs(),
                    Duration.ofNanos(provider.handoffAt(lateOtp.getRecipient().email()) - lateOtpQueuedAt).toMillis(),
                    endStates(batch)
            );
        } finally {
            scheduler.shutdown();
        }
    }

    /** Trabajo de un proceso anterior: tres reclamos sin intentos y dos con intento en curso. */
    private List<UUID> crashClaims() {
        List<UUID> crashed = new ArrayList<>();

        for (int index = 0; index < CRASHED_CLAIMS; index++) {
            Notification notification = seed(false);
            crashed.add(notification.getId());
            ClaimedSchedule claim = claimService.claimDueSchedules(Instant.now(), 1).get(0);

            if (index >= 3) {
                store.begin(claim.scheduleId(), 0, Instant.now());
                NotificationAttempt attempt = NotificationAttempt.create(notification.getId(), claim.scheduleId(),
                        NotificationChannel.EMAIL, "synthetic", 1, Instant.now());
                attempt.markProcessing(Instant.now());
                store.write(DispatchWrite.insert(claim.scheduleId(), 0, attempt, DispatchFinish.CONTINUE, Instant.now()));
            }
        }

        jdbc.update("""
                UPDATE piedrazul.notification_schedule SET processing_started_at = now() - interval '3 hours'
                WHERE notification_id = ANY(?)""", (Object) crashed.toArray(new UUID[0]));
        jdbc.update("""
                UPDATE piedrazul.notification_attempts SET updated_at = now() - interval '3 hours'
                WHERE notification_id = ANY(?)""", (Object) crashed.toArray(new UUID[0]));
        return crashed;
    }

    private Notification seed(boolean otp) {
        Instant now = Instant.now();
        UUID aggregateId = UUID.randomUUID();
        Map<String, String> variables = otp
                ? Map.of(NotificationVariables.CODE, "000111", "expirationMinutes", "5",
                        "destination", "sintetico@example.test",
                        NotificationVariables.EXPIRES_AT, now.plus(Duration.ofMinutes(5)).toString())
                : Map.of("patientName", "Paciente Sintético", "doctorName", "Dra. Sintética",
                        "date", "2026-01-10", "time", "08:00");

        Notification notification = Notification.create(
                otp ? NotificationType.OTP_CODE : NotificationType.APPOINTMENT_REMINDER_2_DAYS,
                new AggregateReference(otp ? AggregateType.VERIFICATION : AggregateType.APPOINTMENT, aggregateId),
                // Un correo por notificación: así el proveedor distingue lo que recibe dos veces.
                new RecipientSnapshot(personId, "Sintético", "+570000000000", aggregateId + "@example.test",
                        Locale.forLanguageTag("es-CO"), null),
                new ChannelPreference(List.of(NotificationChannel.EMAIL)),
                variables,
                now
        );
        notificationRepository.save(notification);
        scheduleRepository.save(NotificationSchedule.create(notification.getId(), LONG_AGO, now));
        return notification;
    }

    private void awaitSettled(List<UUID> batch, List<UUID> crashed, UUID lateOtp) throws InterruptedException {
        List<UUID> all = new ArrayList<>(batch);
        all.addAll(crashed);
        all.add(lateOtp);
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();

        while (System.nanoTime() < deadline) {
            Integer unsettled = jdbc.queryForObject("""
                    SELECT count(*) FROM piedrazul.notification_schedule
                    WHERE notification_id = ANY(?)
                      AND (status = 'PROCESSING' OR (status = 'PENDING' AND COALESCE(next_retry_at, scheduled_at) <= now()))""",
                    Integer.class, (Object) all.toArray(new UUID[0]));

            if (unsettled != null && unsettled == 0) {
                return;
            }

            Thread.sleep(20);
        }

        throw new AssertionError("El escenario no terminó en el plazo");
    }

    private void assertOutcomes(
            List<UUID> batch,
            List<UUID> crashed,
            UUID lateOtp,
            SyntheticProvider provider,
            Profile profile
    ) {
        List<UUID> all = new ArrayList<>(batch);
        all.addAll(crashed);
        all.add(lateOtp);

        // Ningún intento repetido: los reintentos (60 s) quedan fuera de la duración del escenario.
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM (
                    SELECT notification_id FROM piedrazul.notification_attempts
                    WHERE notification_id = ANY(?) GROUP BY notification_id HAVING count(*) > 1
                ) repeated""", Integer.class, (Object) all.toArray(new UUID[0]))).isZero();

        // Dos intentos ya en curso no se reenvían; los tres reclamos sin intentos, sí se envían.
        assertThat(provider.calls.get()).isEqualTo(BATCH + 3 + 1);
        assertThat(provider.handoffs).hasSize(BATCH + 3 + 1);
        assertThat(provider.handoffs.values()).allMatch(handoffs -> handoffs.size() == 1);

        assertThat(jdbc.queryForList("""
                SELECT a.status FROM piedrazul.notification_attempts a
                WHERE a.notification_id = ANY(?) AND a.provider_name = 'synthetic' AND a.error_code = 'ACCEPTANCE_UNKNOWN'
                  AND a.provider_message_id IS NULL AND a.status = 'UNKNOWN' AND a.error_message LIKE 'Intento en curso%'""",
                String.class, (Object) crashed.toArray(new UUID[0]))).hasSize(2);

        // Lo fallido no detuvo lo demás: todo el lote recibió su turno.
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM piedrazul.notification_attempts WHERE notification_id = ANY(?)""",
                Integer.class, (Object) batch.toArray(new UUID[0]))).isEqualTo(BATCH);

        if (profile.faults()) {
            assertThat(endStates(batch)).containsKeys("ACCEPTED/SENT", "PROCESSING/PENDING", "FAILED/FAILED");
        } else {
            assertThat(endStates(batch)).containsOnlyKeys("ACCEPTED/SENT");
        }

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM piedrazul.notification n
                JOIN piedrazul.notification_schedule s ON s.notification_id = n.id
                WHERE n.id = ANY(?) AND s.status IN ('SENT', 'FAILED', 'EXPIRED') AND n.variables_json LIKE '%000111%'""",
                Integer.class, (Object) all.toArray(new UUID[0]))).isZero();
    }

    private Map<String, Integer> endStates(List<UUID> notifications) {
        return jdbc.queryForList("""
                SELECT n.status || '/' || s.status AS state, count(*) AS total
                FROM piedrazul.notification n
                JOIN piedrazul.notification_schedule s ON s.notification_id = n.id
                WHERE n.id = ANY(?) GROUP BY 1""", (Object) notifications.toArray(new UUID[0]))
                .stream()
                .collect(Collectors.toMap(
                        row -> (String) row.get("state"),
                        row -> ((Number) row.get("total")).intValue(),
                        Integer::sum,
                        TreeMap::new
                ));
    }

    private String estimates(Measure fast) {
        double overheadMs = Math.max(0, fast.elapsedMs - fast.simulatedMs) / (double) fast.providerCalls;

        return String.format(Locale.ROOT, """
                Estimación (NO medida), lote de %d con todo @Scheduled en un hilo, sobrecarga medida %.1f ms/envío:
                  proveedor a 0,3 s/envío: %d s bloqueando el programador
                  proveedor a 5 s/envío: %d s
                  proveedor a 30 s/envío (tiempo agotado por envío): %d s
                  llamada sin tiempo máximo: sin cota; se detiene todo @Scheduled mientras dure""",
                BATCH, overheadMs,
                Math.round(BATCH * (300 + overheadMs) / 1000),
                Math.round(BATCH * (5_000 + overheadMs) / 1000),
                Math.round(BATCH * (30_000 + overheadMs) / 1000));
    }

    private NotificationDispatcherService dispatcher(NotificationProvider provider) {
        return new NotificationDispatcherService(
                store,
                new ProviderRegistry(List.of(provider)),
                new TemplateRenderer(new InMemoryNotificationTemplateProvider()),
                new RetryPolicy(),
                new ChannelFallbackPolicy(),
                new RecipientEmailPolicy(Validation.buildDefaultValidatorFactory().getValidator()),
                appointmentId -> true,
                false,
                Clock.systemUTC()
        );
    }

    private record Profile(long latencyMs, boolean faults) {
    }

    private record Measure(
            String name,
            long elapsedMs,
            long simulatedMs,
            int providerCalls,
            long dueJobMaxGapMs,
            long recoveryJobMaxGapMs,
            long foreignJobMaxGapMs,
            long lateOtpWaitMs,
            Map<String, Integer> endStates
    ) {
        @Override
        public String toString() {
            return String.format(Locale.ROOT, """
                    [%s]
                      duración total: %d ms (retardo sintético del proveedor: %d ms), %d llamadas
                      sobrecarga por envío: %.1f ms
                      separación máxima entre ejecuciones: despacho %d ms, recuperación %d ms, job ajeno %d ms
                      espera de un código que llega con el lote en marcha: %d ms
                      estados finales del lote: %s""",
                    name, elapsedMs, simulatedMs, providerCalls,
                    Math.max(0, elapsedMs - simulatedMs) / (double) providerCalls,
                    dueJobMaxGapMs, recoveryJobMaxGapMs, foreignJobMaxGapMs, lateOtpWaitMs, endStates);
        }
    }

    /**
     * Separación máxima entre el fin de una ejecución y el inicio de la siguiente del mismo job. La
     * primera espera cuenta desde el arranque: es la que sufre un job encolado tras el lote.
     */
    private static final class Gaps {
        private volatile long lastEnd;
        private final AtomicLong maxGap = new AtomicLong();

        private Gaps(long start) {
            this.lastEnd = start;
        }

        private Runnable around(Runnable job) {
            return () -> {
                long begin = System.nanoTime();

                maxGap.accumulateAndGet(begin - lastEnd, Math::max);

                try {
                    job.run();
                } finally {
                    lastEnd = System.nanoTime();
                }
            };
        }

        private long maxGapMs() {
            return Duration.ofNanos(maxGap.get()).toMillis();
        }
    }

    private static final class SyntheticProvider implements NotificationProvider {
        private final Profile profile;
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicLong simulatedMs = new AtomicLong();
        private final Map<String, List<Long>> handoffs = new ConcurrentHashMap<>();

        private SyntheticProvider(Profile profile) {
            this.profile = profile;
        }

        private void awaitFirstCall() throws InterruptedException {
            long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();

            while (calls.get() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
        }

        private long handoffAt(String email) {
            return handoffs.get(email).get(0);
        }

        @Override
        public boolean qualifiesForOtpDelivery() {
            return true;
        }

        @Override
        public NotificationChannel channel() {
            return NotificationChannel.EMAIL;
        }

        @Override
        public String providerName() {
            return "synthetic";
        }

        @Override
        public NotificationSendResult send(ChannelMessage message, RecipientSnapshot recipient) {
            int call = calls.getAndIncrement();
            handoffs.computeIfAbsent(recipient.email(), email -> new CopyOnWriteArrayList<>())
                    .add(System.nanoTime());

            boolean hung = profile.faults() && (call == 12 || call == 25 || call == 38);
            sleep(hung ? HUNG_CALL.toMillis() : profile.latencyMs());

            if (profile.faults() && call % 10 == 3) {
                return new NotificationSendResult("synthetic", NotificationChannel.EMAIL, null,
                        AttemptStatus.FAILED, FailureType.TEMPORARY, "SYNTHETIC_THROTTLED", null);
            }

            if (profile.faults() && call % 10 == 7) {
                throw new IllegalStateException("tiempo agotado sintético");
            }

            return new NotificationSendResult("synthetic", NotificationChannel.EMAIL, "synthetic-" + call,
                    AttemptStatus.ACCEPTED, null, null, null);
        }

        private void sleep(long millis) {
            simulatedMs.addAndGet(millis);

            try {
                Thread.sleep(millis);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
        }
    }
}
