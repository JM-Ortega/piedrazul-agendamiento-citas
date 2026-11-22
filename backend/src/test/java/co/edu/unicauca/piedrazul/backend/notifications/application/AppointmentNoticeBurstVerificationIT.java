package co.edu.unicauca.piedrazul.backend.notifications.application;

import co.edu.unicauca.piedrazul.backend.appointment.events.AppointmentCancelledByStaffEvent;
import co.edu.unicauca.piedrazul.backend.appointment.events.AppointmentRescheduledEvent;
import co.edu.unicauca.piedrazul.backend.notifications.api.SendNotificationCommand;
import co.edu.unicauca.piedrazul.backend.notifications.application.eventhandler.AppointmentNotificationEventHandler;
import co.edu.unicauca.piedrazul.backend.notifications.application.template.TemplateRenderer;
import co.edu.unicauca.piedrazul.backend.notifications.domain.message.ChannelMessage;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.*;
import co.edu.unicauca.piedrazul.backend.notifications.domain.policy.ChannelFallbackPolicy;
import co.edu.unicauca.piedrazul.backend.notifications.domain.policy.ReminderScheduleCalculator;
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
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.template.InMemoryNotificationTemplateProvider;
import jakarta.validation.Validation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WU-OE2-APPT-NOTICE-BURST-VERIFY-01. Verificación SINTÉTICA, solo de pruebas, de ráfagas de avisos de
 * cancelación y reagendamiento y de su interferencia con un OTP.
 *
 * <p>Ejercita: manejador de eventos real -> {@code NotificationModuleApiImpl.sendNow} real -> persistencia
 * real (notification + notification_schedule) en PostgreSQL 16 -> {@code DueNotificationJob} real (lote de
 * 50, {@code FOR UPDATE SKIP LOCKED}) bajo un {@code ThreadPoolTaskScheduler} de UN hilo (el valor por
 * defecto de producción) -> {@code NotificationDispatcherService} real -> proveedor SINTÉTICO.
 *
 * <p>Evita: el publicador de eventos de Spring Modulith (se invoca el manejador directamente, como
 * {@code AppointmentChangeNoticeIdempotencyIT}), {@code VerificationService} (el OTP se crea con el mismo
 * comando que {@code NotificationVerificationCodeSender}), SES/SMTP (proveedor sintético), la
 * elegibilidad de cita (siempre verdadera; los avisos de cancelación no la exigen) y el retardo real
 * {@code notifications.due-job.fixed-delay} de 30 s salvo en el escenario marcado como tal.
 *
 * <p>Las mediciones se añaden a {@value #EVIDENCE}. Datos exclusivamente sintéticos.
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
class AppointmentNoticeBurstVerificationIT {

    /**
     * Contenedor PostgreSQL PROPIO y desechable, deliberadamente NO el compartido de
     * {@code PostgresIntegrationSupport}: el job real reclama de forma global y, en el escenario de 63 s,
     * reintentos que otras clases dejan pendientes (60 s) vencerían y serían despachados por esta prueba
     * (observado: tercer lote de 7 en lugar de 1). Con base propia ninguna fila ajena puede entrar ni salir.
     * Misma imagen, script de roles, migraciones Flyway y propiedades que el soporte compartido.
     */
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine")).withInitScript("db/test-roles.sql");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void registerDataSource(DynamicPropertyRegistry registry) {
        String jdbcUrl = POSTGRES.getJdbcUrl();
        registry.add("spring.datasource.url",
                () -> jdbcUrl + (jdbcUrl.contains("?") ? "&" : "?") + "currentSchema=piedrazul,extensions");
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.placeholders.app_role", () -> co.edu.unicauca.piedrazul.backend.support.PostgresIntegrationSupport.APP_ROLE);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }


    private static final String EVIDENCE = System.getProperty("burst.evidence",
            "/tmp/piedrazul/wu-oe2-burst/burst-evidence.txt");
    private static final int BATCH = 50;
    private static final Duration OTP_VALIDITY = Duration.ofMinutes(5); // VerificationService.EXPIRATION_MINUTES

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

    private UUID person;
    /** Agregados creados por esta clase (avisos y OTP): lo único que cleanUp() elimina. */
    private final List<UUID> ownAggregates = new CopyOnWriteArrayList<>();
    private NotificationModuleApiImpl api;
    private AppointmentNotificationEventHandler handler;

    @BeforeEach
    void setUp() {
        // Base propia (ver POSTGRES): no se muta nada ajeno. Esta comprobación solo confirma el aislamiento;
        // todo lo que la clase crea se registra y se borra en cleanUp().
        Integer foreignActive = jdbc.queryForObject("""
                SELECT count(*) FROM piedrazul.notification_schedule
                WHERE status = 'PROCESSING' OR (status = 'PENDING' AND COALESCE(next_retry_at, scheduled_at) <= now())""",
                Integer.class);
        assertThat(foreignActive).as("trabajo activo o vencido en la base propia de la clase (aislamiento roto)").isZero();

        NotificationFactory factory = new NotificationFactory();
        NotificationOrchestrator orchestrator = new NotificationOrchestrator(notificationRepository, factory,
                new NotificationSchedulerService(factory, scheduleRepository), new ChannelFallbackPolicy());
        api = new NotificationModuleApiImpl(orchestrator, null, Clock.systemUTC());
        handler = new AppointmentNotificationEventHandler(api,
                new ReminderScheduleCalculator(ZoneId.of("America/Bogota")), Clock.systemUTC());

        person = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO piedrazul.person (id, identification_type, identification, first_name, last_name, phone, email)
                VALUES (?, 'CEDULA', ?, 'Sintético', 'Prueba', '+570000000000', 'sintetico@example.test')""",
                person, "burst-" + person);
    }

    @AfterEach
    void cleanUp() {
        // También en caminos de fallo. Incluye el trabajo reclamado y no despachado de S6.
        Object[] own = ownAggregates.toArray(new UUID[0]);
        jdbc.update("""
                DELETE FROM piedrazul.notification_attempts WHERE notification_id IN
                (SELECT id FROM piedrazul.notification WHERE aggregate_id = ANY(?))""", (Object) own);
        jdbc.update("""
                DELETE FROM piedrazul.notification_schedule WHERE notification_id IN
                (SELECT id FROM piedrazul.notification WHERE aggregate_id = ANY(?))""", (Object) own);
        jdbc.update("DELETE FROM piedrazul.notification WHERE aggregate_id = ANY(?)", (Object) own);
        jdbc.update("DELETE FROM piedrazul.person WHERE id = ?", person);
    }

    // ── Escenarios ──────────────────────────────────────────────────────

    /** 50 cancelaciones. Latencia 20 ms, sin fallos, intervalo del job reducido a 100 ms. */
    @Test
    void s1_fiftyCancellations() throws Exception {
        Result r = runBurst("S1 50 cancelaciones; latencia 20 ms; sin fallos; intervalo job 100 ms",
                50, 0, new Profile(20, false), Duration.ofMillis(100), OTP_VALIDITY, false);

        assertThat(r.created).isEqualTo(Map.of("APPOINTMENT_CANCELLED", 50));
        assertThat(r.batchSizes).containsExactly(50, 1); // lote de avisos y luego el OTP
        assertThat(r.burstEndStates).containsOnlyKeys("ACCEPTED/SENT");
        assertThat(r.otpState).isEqualTo("ACCEPTED/SENT");
    }

    /** 100 mixtos (50 cancelaciones + 50 reagendamientos). Se comprueba además la reentrega de los 100 eventos. */
    @Test
    void s2_hundredMixed() throws Exception {
        Result r = runBurst("S2 100 mixtos (50+50); latencia 20 ms; sin fallos; intervalo job 100 ms",
                50, 50, new Profile(20, false), Duration.ofMillis(100), OTP_VALIDITY, true);

        assertThat(r.created).isEqualTo(Map.of("APPOINTMENT_CANCELLED", 50, "APPOINTMENT_RESCHEDULED", 50));
        // Más allá de los primeros 50: segundo lote; el OTP, programado después, queda en el tercero.
        assertThat(r.batchSizes).containsExactly(50, 50, 1);
        assertThat(r.burstEndStates).containsOnlyKeys("ACCEPTED/SENT");
        assertThat(r.otpState).isEqualTo("ACCEPTED/SENT");
    }

    /** 100 mixtos con latencia 100 ms y fallos deterministas (cada 10 llamadas: temporal en 3, excepción en 7). */
    @Test
    void s3_hundredMixedWithLatencyAndFailures() throws Exception {
        Result r = runBurst("S3 100 mixtos; latencia 100 ms; fallos 3/10 temporal, 7/10 excepción; intervalo job 100 ms",
                50, 50, new Profile(100, true), Duration.ofMillis(100), OTP_VALIDITY, true);

        assertThat(r.batchSizes).containsExactly(50, 50, 1);
        // 80 aceptadas; 10 con excepción del proveedor = ACEPTACIÓN DESCONOCIDA terminal (no es no-entrega
        // confirmada: el correo pudo salir); 10 rechazos temporales probados = reintento futuro.
        assertThat(r.burstEndStates).isEqualTo(Map.of("ACCEPTED/SENT", 80, "FAILED/FAILED", 10, "PROCESSING/PENDING", 10));
        assertThat(r.attemptStates).isEqualTo(Map.of("ACCEPTED/-", 80, "FAILED/SYNTHETIC_THROTTLED", 10,
                "UNKNOWN/ACCEPTANCE_UNKNOWN", 10));
        assertThat(r.unknownTerminal).isEqualTo(10);
        assertThat(r.throttledRetryable).isEqualTo(10);
        assertThat(r.otpState).isEqualTo("ACCEPTED/SENT");
    }

    /**
     * 50 cancelaciones a 100 ms (~5 s) y un OTP cuya validez se comprime a 2 s (la real es 5 min; se comprime
     * solo para observar el vencimiento sin esperar minutos). El OTP debe expirar sin llegar al proveedor.
     */
    @Test
    void s4_otpExpiresWhileWaitingBehindTheBurst() throws Exception {
        Result r = runBurst("S4 50 cancelaciones; latencia 100 ms; OTP con validez comprimida a 2 s; intervalo job 100 ms",
                50, 0, new Profile(100, false), Duration.ofMillis(100), Duration.ofSeconds(2), false);

        assertThat(r.otpHandoffs).isZero();
        assertThat(r.otpState).isEqualTo("EXPIRED/EXPIRED");
        assertThat(r.otpCodeScrubbed).isTrue();
        assertThat(r.burstEndStates).containsOnlyKeys("ACCEPTED/SENT");
    }

    /**
     * Mismo caso que S2 pero con el intervalo REAL de producción (fixedDelay 30 s) y un solo hilo: es la
     * única medición que no sustituye la configuración del job. Dura ~1 min.
     */
    @Test
    void s5_hundredMixedWithTheProductionFixedDelay() throws Exception {
        Result r = runBurst("S5 100 mixtos; latencia 20 ms; intervalo job REAL 30000 ms; 1 hilo",
                50, 50, new Profile(20, false), Duration.ofSeconds(30), OTP_VALIDITY, false);

        assertThat(r.batchSizes).containsExactly(50, 50, 1);
        assertThat(r.otpState).isEqualTo("ACCEPTED/SENT");
        assertThat(r.otpHandoffs).isEqualTo(1);
    }

    /** Dos reclamos simultáneos de 50 sobre 100 pendientes: conjuntos disjuntos, sin pérdidas. */
    @Test
    void s6_twoConcurrentBatchClaimsOverTheBurstAreDisjoint() throws Exception {
        // Los 100 reclamos quedan PROCESSING a propósito (reclamado, nunca despachado): no es trabajo varado
        // de producción; cleanUp() los elimina, también si una aserción falla.
        for (int i = 0; i < 100; i++) {
            AppointmentCancelledByStaffEvent event = cancelled(i);
            ownAggregates.add(event.appointmentId());
            handler.onAppointmentCancelledByStaff(event);
        }
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            List<Future<List<ClaimedSchedule>>> futures = new ArrayList<>();
            for (int w = 0; w < 2; w++) {
                futures.add(pool.submit(() -> {
                    barrier.await();
                    return claimService.claimDueSchedules(Instant.now(), BATCH);
                }));
            }
            Set<UUID> first = ids(futures.get(0).get(30, TimeUnit.SECONDS));
            Set<UUID> second = ids(futures.get(1).get(30, TimeUnit.SECONDS));

            Set<UUID> union = new HashSet<>(first);
            union.addAll(second);
            assertThat(first.size() + second.size()).isEqualTo(union.size()); // sin solapes
            assertThat(union).hasSize(100); // sin pérdidas
            assertThat(claimService.claimDueSchedules(Instant.now(), BATCH)).isEmpty();
            append("S6 reclamos concurrentes: lote A=" + first.size() + ", lote B=" + second.size()
                    + ", solapados=" + (first.size() + second.size() - union.size()));
        } finally {
            pool.shutdownNow();
        }
    }

    // ── Motor del escenario ─────────────────────────────────────────────

    private Result runBurst(String name, int cancellations, int reschedulings, Profile profile, Duration jobDelay,
                            Duration otpValidity, boolean redeliver) throws Exception {
        int total = cancellations + reschedulings;
        List<AppointmentCancelledByStaffEvent> cancelledEvents = new ArrayList<>();
        List<AppointmentRescheduledEvent> rescheduledEvents = new ArrayList<>();
        List<UUID> aggregates = new ArrayList<>();

        // Cada evento usa un agregado distinto: la idempotencia no colapsa la ráfaga.
        long createStart = System.nanoTime();
        for (int i = 0; i < cancellations; i++) {
            AppointmentCancelledByStaffEvent event = cancelled(i);
            cancelledEvents.add(event);
            aggregates.add(event.appointmentId());
            ownAggregates.add(event.appointmentId());
            handler.onAppointmentCancelledByStaff(event);
        }
        for (int i = 0; i < reschedulings; i++) {
            AppointmentRescheduledEvent event = rescheduled(cancellations + i);
            rescheduledEvents.add(event);
            aggregates.add(event.previousAppointmentId());
            ownAggregates.add(event.previousAppointmentId());
            handler.onAppointmentRescheduled(event);
        }
        long createMs = Duration.ofNanos(System.nanoTime() - createStart).toMillis();

        Map<String, Integer> created = countBy("""
                SELECT n.type_code AS k, count(*) AS total FROM piedrazul.notification n
                WHERE n.aggregate_id = ANY(?) GROUP BY 1""", aggregates);
        int schedules = jdbc.queryForObject("""
                SELECT count(*) FROM piedrazul.notification_schedule s JOIN piedrazul.notification n ON n.id = s.notification_id
                WHERE n.aggregate_id = ANY(?) AND s.status = 'PENDING'""", Integer.class, (Object) aggregates.toArray(new UUID[0]));
        assertThat(schedules).isEqualTo(total);

        SyntheticProvider provider = new SyntheticProvider(profile);
        DueNotificationJob job = new DueNotificationJob(claimService, dispatcher(provider), Clock.systemUTC());

        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1); // el valor por defecto de producción (no se configura spring.task.scheduling.pool.size)
        scheduler.setThreadNamePrefix("burst-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        scheduler.initialize();

        List<Run> runs = new CopyOnWriteArrayList<>();
        long start = System.nanoTime();
        Gaps foreign = new Gaps(start);
        UUID otpId;
        long otpQueuedAt;
        String otpEmail = "otp-" + UUID.randomUUID() + "@example.test";
        UUID otpVerification = UUID.randomUUID();
        ownAggregates.add(otpVerification);
        List<UUID> all = new ArrayList<>();

        try {
            scheduler.scheduleWithFixedDelay(() -> {
                int before = provider.calls.get();
                long begin = System.nanoTime();
                try {
                    job.processDueNotifications();
                } finally {
                    int delta = provider.calls.get() - before;
                    int stuck = jdbc.queryForObject(
                            "SELECT count(*) FROM piedrazul.notification_schedule WHERE status = 'PROCESSING'", Integer.class);
                    runs.add(new Run(Duration.ofNanos(begin - start).toMillis(),
                            Duration.ofNanos(System.nanoTime() - start).toMillis(), delta, stuck));
                }
            }, jobDelay);
            scheduler.scheduleWithFixedDelay(foreign.around(() -> { }), Duration.ofMillis(50));

            // El OTP llega con el primer lote ya en marcha (después de la primera entrega al proveedor).
            provider.awaitFirstCall();
            Instant expiry = Instant.now().plus(otpValidity);
            otpId = api.sendNow(new SendNotificationCommand(
                    NotificationType.OTP_CODE,
                    new AggregateReference(AggregateType.VERIFICATION, otpVerification),
                    new RecipientSnapshot(person, "Sintético", null, otpEmail, Locale.forLanguageTag("es-CO"), null),
                    Map.of(NotificationVariables.CODE, "000111", "expirationMinutes", "5",
                            NotificationVariables.EXPIRES_AT, expiry.toString(), "destination", otpEmail)));
            otpQueuedAt = System.nanoTime();

            List<UUID> burstIds = jdbc.queryForList(
                    "SELECT id FROM piedrazul.notification WHERE aggregate_id = ANY(?)", UUID.class, (Object) aggregates.toArray(new UUID[0]));
            all.addAll(burstIds);
            all.add(otpId);
            awaitSettled(all);
        } finally {
            scheduler.shutdown();
        }

        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
        List<UUID> burstIds = new ArrayList<>(all);
        burstIds.remove(otpId);

        // Redelivery of every event after the burst settled: no new rows, no new handoffs.
        int redeliveryRows = -1;
        int callsBeforeRedelivery = provider.calls.get();
        if (redeliver) {
            cancelledEvents.forEach(handler::onAppointmentCancelledByStaff);
            rescheduledEvents.forEach(handler::onAppointmentRescheduled);
            redeliveryRows = jdbc.queryForObject("SELECT count(*) FROM piedrazul.notification WHERE aggregate_id = ANY(?)",
                    Integer.class, (Object) aggregates.toArray(new UUID[0]));
            DueNotificationJob drain = new DueNotificationJob(claimService, dispatcher(provider), Clock.systemUTC());
            drain.processDueNotifications();
            assertThat(redeliveryRows).isEqualTo(total);
            assertThat(provider.calls.get()).isEqualTo(callsBeforeRedelivery);
        }

        // ── Contabilidad ──
        Map<String, Integer> burstEnd = endStates(burstIds);
        String otpState = endStates(List.of(otpId)).keySet().iterator().next();
        int attempts = jdbc.queryForObject("SELECT count(*) FROM piedrazul.notification_attempts WHERE notification_id = ANY(?)",
                Integer.class, (Object) burstIds.toArray(new UUID[0]));
        Map<String, Integer> attemptStates = jdbc.queryForList(
                "SELECT status || '/' || coalesce(error_code, '-') AS k, count(*) AS total FROM piedrazul.notification_attempts "
                        + "WHERE notification_id = ANY(?) GROUP BY 1", (Object) burstIds.toArray(new UUID[0])).stream()
                .collect(Collectors.toMap(row -> (String) row.get("k"), row -> ((Number) row.get("total")).intValue(),
                        Integer::sum, TreeMap::new));
        int duplicateNotifications = jdbc.queryForObject("""
                SELECT count(*) FROM (SELECT aggregate_id, type_code FROM piedrazul.notification WHERE aggregate_id = ANY(?)
                GROUP BY 1, 2 HAVING count(*) > 1) d""", Integer.class, (Object) aggregates.toArray(new UUID[0]));
        long duplicateHandoffs = provider.handoffs.values().stream().filter(l -> l.size() > 1).count();
        int burstHandoffs = (int) provider.handoffs.keySet().stream().filter(e -> !e.equals(otpEmail)).count();
        int otpHandoffs = provider.handoffs.getOrDefault(otpEmail, List.of()).size();
        Long otpWaitMs = otpHandoffs == 0 ? null
                : Duration.ofNanos(provider.handoffs.get(otpEmail).get(0) - otpQueuedAt).toMillis();
        boolean scrubbed = jdbc.queryForObject("""
                SELECT count(*) FROM piedrazul.notification WHERE id = ? AND variables_json LIKE '%000111%'""",
                Integer.class, otpId) == 0;
        int stranded = jdbc.queryForObject("""
                SELECT count(*) FROM piedrazul.notification_schedule WHERE notification_id = ANY(?) AND
                (status = 'PROCESSING' OR (status = 'PENDING' AND COALESCE(next_retry_at, scheduled_at) <= now()))""",
                Integer.class, (Object) all.toArray(new UUID[0]));
        int retryScheduledInFuture = jdbc.queryForObject("""
                SELECT count(*) FROM piedrazul.notification_schedule WHERE notification_id = ANY(?) AND status = 'PENDING'
                AND next_retry_at > now()""", Integer.class, (Object) burstIds.toArray(new UUID[0]));
        List<Run> working = runs.stream().filter(r -> r.handoffs > 0).toList();
        List<Integer> batchSizes = working.stream().map(r -> r.handoffs).toList();

        // Aserciones comunes a todos los escenarios
        assertThat(created.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(total);
        assertThat(duplicateNotifications).isZero();
        assertThat(duplicateHandoffs).isZero();
        assertThat(burstEnd.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(total); // nada descartado
        assertThat(stranded).isZero();
        assertThat(working.stream().mapToInt(r -> r.stuckAfter).max().orElse(0)).isZero(); // lotes completos
        assertThat(runs.stream().mapToInt(Run::handoffs).max().orElse(0)).isLessThanOrEqualTo(BATCH);

        StringBuilder out = new StringBuilder();
        out.append("== ").append(name).append('\n')
                .append(String.format(Locale.ROOT, "  creación vía manejador de eventos: %d notificaciones en %d ms; programaciones PENDING: %d%n",
                        total, createMs, schedules))
                .append("  creadas por tipo: ").append(created).append('\n')
                .append("  perfil proveedor: latencia ").append(profile.latencyMs).append(" ms, fallos=").append(profile.faults).append('\n')
                .append("  lotes con trabajo (entregas por ejecución del job): ").append(batchSizes).append('\n')
                .append("  ejecuciones (inicio_ms-fin_ms/entregas/PROCESSING_restantes): ").append(runs.stream().map(Run::toString).toList()).append('\n')
                .append("  entregas al proveedor sintético: ráfaga ").append(burstHandoffs).append(", OTP ").append(otpHandoffs)
                .append(", llamadas totales ").append(callsBeforeRedelivery).append('\n')
                .append("  intentos persistidos de la ráfaga: ").append(attempts).append('\n')
                .append("  estados finales ráfaga (notificación/schedule): ").append(burstEnd).append('\n')
                .append("  intentos por estado/código de error: ").append(attemptStates).append('\n')
                .append("  reintentos futuros pendientes (PENDING con next_retry_at): ").append(retryScheduledInFuture).append('\n')
                .append("  duplicados: filas ").append(duplicateNotifications).append(", entregas ").append(duplicateHandoffs)
                .append("; trabajo varado: ").append(stranded).append('\n')
                .append("  reentrega de los eventos: ").append(redeliver ? "filas=" + redeliveryRows + ", entregas nuevas=0" : "no aplicada").append('\n')
                .append(String.format(Locale.ROOT, "  OTP: validez %d s, espera hasta el proveedor %s, estado final %s, código eliminado=%s%n",
                        otpValidity.toSeconds(), otpWaitMs == null ? "N/A (no invocado)" : otpWaitMs + " ms", otpState, scrubbed))
                .append(String.format(Locale.ROOT, "  duración total hasta estabilizar: %d ms; separación máxima del job ajeno (50 ms): %d ms%n",
                        elapsedMs, foreign.maxGapMs()));
        append(out.toString());
        System.out.println(out);

        int unknownTerminal = jdbc.queryForObject("""
                SELECT count(*) FROM piedrazul.notification n JOIN piedrazul.notification_schedule s ON s.notification_id = n.id
                WHERE n.id = ANY(?) AND n.status = 'FAILED' AND s.status = 'FAILED' AND EXISTS (
                  SELECT 1 FROM piedrazul.notification_attempts a WHERE a.notification_id = n.id
                  AND a.status = 'UNKNOWN' AND a.error_code = 'ACCEPTANCE_UNKNOWN')
                AND NOT EXISTS (SELECT 1 FROM piedrazul.notification_attempts a WHERE a.notification_id = n.id
                  AND a.error_code <> 'ACCEPTANCE_UNKNOWN')""", Integer.class, (Object) burstIds.toArray(new UUID[0]));
        int throttledRetryable = jdbc.queryForObject("""
                SELECT count(*) FROM piedrazul.notification n JOIN piedrazul.notification_schedule s ON s.notification_id = n.id
                WHERE n.id = ANY(?) AND n.status = 'PROCESSING' AND s.status = 'PENDING' AND s.next_retry_at > now()
                AND EXISTS (SELECT 1 FROM piedrazul.notification_attempts a WHERE a.notification_id = n.id
                  AND a.status = 'FAILED' AND a.error_code = 'SYNTHETIC_THROTTLED')""", Integer.class, (Object) burstIds.toArray(new UUID[0]));
        return new Result(created, batchSizes, burstEnd, otpState, otpHandoffs, scrubbed, attemptStates,
                unknownTerminal, throttledRetryable);
    }

    // ── Soporte ─────────────────────────────────────────────────────────

    private void awaitSettled(List<UUID> all) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofMinutes(4).toNanos();
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

    private Map<String, Integer> endStates(List<UUID> ids) {
        return countBy("""
                SELECT n.status || '/' || s.status AS k, count(*) AS total FROM piedrazul.notification n
                JOIN piedrazul.notification_schedule s ON s.notification_id = n.id WHERE n.id = ANY(?) GROUP BY 1""", ids);
    }

    private Map<String, Integer> countBy(String sql, List<UUID> ids) {
        return jdbc.queryForList(sql, (Object) ids.toArray(new UUID[0])).stream().collect(Collectors.toMap(
                row -> (String) row.get("k"), row -> ((Number) row.get("total")).intValue(), Integer::sum, TreeMap::new));
    }

    private static Set<UUID> ids(List<ClaimedSchedule> claims) {
        return claims.stream().map(ClaimedSchedule::scheduleId).collect(Collectors.toSet());
    }

    private static synchronized void append(String text) {
        try {
            Path path = Path.of(EVIDENCE);
            Files.createDirectories(path.getParent());
            Files.writeString(path, text + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private AppointmentCancelledByStaffEvent cancelled(int index) {
        return new AppointmentCancelledByStaffEvent(UUID.randomUUID(), person, "Paciente Sintético " + index, "+570000000000",
                "burst-" + UUID.randomUUID() + "@example.test", "Dra. Sintética", LocalDate.of(2026, 3, 10),
                LocalTime.of(14, 30), "FISIOTERAPIA");
    }

    private AppointmentRescheduledEvent rescheduled(int index) {
        return new AppointmentRescheduledEvent(UUID.randomUUID(), UUID.randomUUID(), person, "Paciente Sintético " + index,
                "+570000000000", "burst-" + UUID.randomUUID() + "@example.test", "Dr. Anterior", LocalDate.of(2026, 3, 10),
                LocalTime.of(8, 0), "FISIOTERAPIA", "Dra. Nueva", LocalDate.of(2026, 3, 12), LocalTime.of(15, 0),
                "QUIROPRAXIA");
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
                Clock.systemUTC());
    }

    private record Profile(long latencyMs, boolean faults) {
    }

    private record Run(long startMs, long endMs, int handoffs, int stuckAfter) {
        @Override
        public String toString() {
            return startMs + "-" + endMs + "/" + handoffs + "/" + stuckAfter;
        }
    }

    private record Result(Map<String, Integer> created, List<Integer> batchSizes, Map<String, Integer> burstEndStates,
                          String otpState, int otpHandoffs, boolean otpCodeScrubbed,
                          Map<String, Integer> attemptStates, int unknownTerminal, int throttledRetryable) {
    }

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

    /** Proveedor de correo sintético: latencia fija, fallos deterministas por índice de llamada. */
    private static final class SyntheticProvider implements NotificationProvider {
        private final Profile profile;
        private final AtomicInteger calls = new AtomicInteger();
        private final Map<String, List<Long>> handoffs = new ConcurrentHashMap<>();

        private SyntheticProvider(Profile profile) {
            this.profile = profile;
        }

        private void awaitFirstCall() throws InterruptedException {
            long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            while (calls.get() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(2);
            }
            if (calls.get() == 0) {
                throw new AssertionError("El proveedor no fue invocado en 60 s: el job no despachó el primer aviso");
            }
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
            handoffs.computeIfAbsent(recipient.email(), email -> new CopyOnWriteArrayList<>()).add(System.nanoTime());
            try {
                Thread.sleep(profile.latencyMs);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
            if (profile.faults && call % 10 == 3) {
                return new NotificationSendResult("synthetic", NotificationChannel.EMAIL, null,
                        AttemptStatus.FAILED, FailureType.TEMPORARY, "SYNTHETIC_THROTTLED", null);
            }
            if (profile.faults && call % 10 == 7) {
                throw new IllegalStateException("tiempo agotado sintético");
            }
            return new NotificationSendResult("synthetic", NotificationChannel.EMAIL, "synthetic-" + call,
                    AttemptStatus.ACCEPTED, null, null, null);
        }
    }
}
