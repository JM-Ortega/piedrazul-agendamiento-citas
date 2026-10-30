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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Garantías del despacho que dependen de transacciones y bloqueos reales de PostgreSQL: escritura
 * terminal atómica, limpieza del código en {@code variables_json}, cerca de propiedad y que el job
 * de intentos antiguos no reclasifica una aceptación. El esquema lo crean las migraciones reales.
 * La prueba multihilo completa de reclamo y recuperación corresponde a W1b.
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
class NotificationDispatchPersistenceIT extends PostgresIntegrationSupport {

    private static final String SYNTHETIC_CODE = "000111";
    // Anterior a cualquier otro schedule pendiente de la base compartida: el reclamo toma este.
    private static final Instant LONG_AGO = Instant.parse("2000-01-01T00:00:00Z");

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
    private JdbcTemplate jdbc;

    private UUID personId;

    @BeforeEach
    void seedPerson() {
        personId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO piedrazul.person (id, identification_type, identification, first_name, last_name, phone, email)
                VALUES (?, 'CEDULA', ?, 'Sintético', 'Prueba', '+570000000000', 'sintetico@example.test')""",
                personId, "w1a-" + personId);
    }

    // ── 1 y 2: escritura terminal atómica y sin código en claro ─────────

    @Test
    void terminalTransitionIsAtomicAndRemovesPlaintextCode() {
        Notification notification = seedOtp();
        ClaimedSchedule claim = claim(notification);
        NotificationDispatchStore.Begin begin = store.begin(claim.scheduleId(), claim.attemptCount(), Instant.now());
        assertThat(begin.status()).isEqualTo(NotificationDispatchStore.Begin.Status.READY);

        NotificationAttempt attempt = NotificationAttempt.create(notification.getId(), claim.scheduleId(),
                NotificationChannel.EMAIL, "stub-email", 1, Instant.now());
        attempt.markProcessing(Instant.now());
        assertThat(store.write(DispatchWrite.insert(claim.scheduleId(), 0, attempt, DispatchFinish.CONTINUE, Instant.now())))
                .isEqualTo(NotificationDispatchStore.WriteResult.APPLIED);

        // Una transición que falla a mitad (reintento sin fecha) no deja nada a medias.
        attempt.markAccepted("stub-message", Instant.now());
        assertThatThrownBy(() -> store.write(new DispatchWrite(claim.scheduleId(), 1, null, attempt,
                DispatchFinish.RETRY, null, Instant.now())))
                .isInstanceOf(NullPointerException.class);

        assertRow(notification, "PROCESSING", "PROCESSING", "PROCESSING");
        assertThat(variablesJson(notification)).contains(SYNTHETIC_CODE);

        // La escritura válida cambia intento, notificación y schedule juntos y elimina el código.
        assertThat(store.write(DispatchWrite.resolve(claim.scheduleId(), 1, attempt, DispatchFinish.ACCEPTED,
                null, Instant.now()))).isEqualTo(NotificationDispatchStore.WriteResult.APPLIED);

        assertRow(notification, "ACCEPTED", "SENT", "ACCEPTED");
        assertCodeGoneFromDatabase(notification);
    }

    @Test
    void acceptanceUnknownIsPersistedWithoutPlaintextCode() {
        Notification notification = seedOtp();
        ClaimedSchedule claim = claim(notification);
        Stub email = new Stub(() -> {
            throw new IllegalStateException("tiempo agotado sintético");
        });

        dispatcher(email).dispatch(claim);

        assertThat(email.calls).isEqualTo(1);
        assertRow(notification, "FAILED", "FAILED", "UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT error_code FROM piedrazul.notification_attempts WHERE notification_id = ?",
                String.class, notification.getId())).isEqualTo("ACCEPTANCE_UNKNOWN");
        assertCodeGoneFromDatabase(notification);
    }

    @Test
    void retryKeepsCodeUntilTheDispatchEnds() {
        Notification notification = seedOtp();
        ClaimedSchedule claim = claim(notification);

        dispatcher(new Stub(() -> result(AttemptStatus.FAILED, FailureType.TEMPORARY))).dispatch(claim);

        assertRow(notification, "PROCESSING", "PENDING", "FAILED");
        assertThat(variablesJson(notification)).contains(SYNTHETIC_CODE);
    }

    @Test
    void providerDiagnosticsEchoingTheCodeAreNotPersisted() {
        Notification notification = seedOtp();
        ClaimedSchedule claim = claim(notification);
        Stub email = new Stub(() -> new NotificationSendResult("stub-email", NotificationChannel.EMAIL, null,
                AttemptStatus.FAILED, FailureType.TEMPORARY, "ECHO_" + SYNTHETIC_CODE,
                "el proveedor repite: tu código es " + SYNTHETIC_CODE));

        dispatcher(email).dispatch(claim);

        // La clasificación no cambia: reintento programado, código aún necesario en las variables.
        assertRow(notification, "PROCESSING", "PENDING", "FAILED");
        Map<String, Object> attempt = jdbc.queryForMap("""
                SELECT error_code, error_message, failure_type FROM piedrazul.notification_attempts
                WHERE notification_id = ?""", notification.getId());
        assertThat(attempt.get("failure_type")).isEqualTo("TEMPORARY");
        assertThat(String.valueOf(attempt.get("error_code"))).doesNotContain(SYNTHETIC_CODE);
        assertThat(String.valueOf(attempt.get("error_message"))).doesNotContain(SYNTHETIC_CODE);
    }

    // ── 3: un reclamo obsoleto no invoca ni sobrescribe ─────────────────

    @Test
    void obsoleteClaimNeverInvokesTheProvider() {
        Notification notification = seedOtp();
        ClaimedSchedule claim = claim(notification);
        // Un trabajador más nuevo ya registró su intento en curso.
        NotificationAttempt newer = NotificationAttempt.create(notification.getId(), claim.scheduleId(),
                NotificationChannel.EMAIL, "stub-email", 1, Instant.now());
        newer.markProcessing(Instant.now());
        attemptRepository.save(newer);
        Stub email = new Stub(() -> result(AttemptStatus.ACCEPTED, null));

        dispatcher(email).dispatch(claim);

        assertThat(email.calls).isZero();
        assertThat(attemptRepository.countByNotificationId(notification.getId())).isEqualTo(1);
        assertRow(notification, "PENDING", "PROCESSING", "PROCESSING");
    }

    @Test
    void lostOwnershipCannotOverwriteNewerState() {
        Notification notification = seedOtp();
        ClaimedSchedule claim = claim(notification);
        store.begin(claim.scheduleId(), 0, Instant.now());
        NotificationAttempt attempt = NotificationAttempt.create(notification.getId(), claim.scheduleId(),
                NotificationChannel.EMAIL, "stub-email", 1, Instant.now());
        attempt.markProcessing(Instant.now());
        store.write(DispatchWrite.insert(claim.scheduleId(), 0, attempt, DispatchFinish.CONTINUE, Instant.now()));

        // Otro proceso cierra el schedule mientras el proveedor responde.
        jdbc.update("UPDATE piedrazul.notification_schedule SET status = 'CANCELLED' WHERE id = ?", claim.scheduleId());
        attempt.markAccepted("stub-message", Instant.now());

        assertThat(store.write(DispatchWrite.resolve(claim.scheduleId(), 1, attempt, DispatchFinish.ACCEPTED,
                null, Instant.now()))).isEqualTo(NotificationDispatchStore.WriteResult.OWNERSHIP_LOST);

        // Solo se registra el hecho propio (el intento); notificación y schedule no se tocan.
        assertRow(notification, "PROCESSING", "CANCELLED", "ACCEPTED");
    }

    @Test
    void staleAttemptCountIsRejectedAtInsert() {
        Notification notification = seedOtp();
        ClaimedSchedule claim = claim(notification);
        store.begin(claim.scheduleId(), 0, Instant.now());
        NotificationAttempt attempt = NotificationAttempt.create(notification.getId(), claim.scheduleId(),
                NotificationChannel.EMAIL, "stub-email", 1, Instant.now());
        attempt.markProcessing(Instant.now());

        assertThat(store.write(DispatchWrite.insert(claim.scheduleId(), 5, attempt, DispatchFinish.CONTINUE, Instant.now())))
                .isEqualTo(NotificationDispatchStore.WriteResult.OWNERSHIP_LOST);
        assertThat(attemptRepository.countByNotificationId(notification.getId())).isZero();
    }

    // ── 4: el job de intentos antiguos no reclasifica una aceptación ────

    @Test
    void acceptedAttemptIsNotReclassifiedByTheStaleJob() {
        Notification notification = seedOtp();
        ClaimedSchedule claim = claim(notification);
        dispatcher(new Stub(() -> result(AttemptStatus.ACCEPTED, null))).dispatch(claim);
        assertRow(notification, "ACCEPTED", "SENT", "ACCEPTED");

        jdbc.update("UPDATE piedrazul.notification_attempts SET updated_at = now() - interval '3 hours' WHERE notification_id = ?",
                notification.getId());

        new StaleNotificationAttemptJob(attemptRepository, Clock.systemUTC(), true, Duration.ofHours(2))
                .processStaleAttempts();

        assertRow(notification, "ACCEPTED", "SENT", "ACCEPTED");
        assertThat(attemptRepository.findStaleAttempts(Instant.now().minus(Duration.ofHours(2)), 500))
                .noneMatch(stale -> stale.getNotificationId().equals(notification.getId()));
    }

    // ── Soporte ─────────────────────────────────────────────────────────

    private Notification seedOtp() {
        Instant now = Instant.now();
        Notification notification = Notification.create(
                NotificationType.OTP_CODE,
                new AggregateReference(AggregateType.VERIFICATION, UUID.randomUUID()),
                new RecipientSnapshot(personId, "Sintético", "+570000000000", "sintetico@example.test",
                        Locale.forLanguageTag("es-CO"), null),
                new ChannelPreference(List.of(NotificationChannel.EMAIL)),
                Map.of(NotificationVariables.CODE, SYNTHETIC_CODE, "expirationMinutes", "5",
                        "destination", "sintetico@example.test",
                        NotificationVariables.EXPIRES_AT, now.plus(Duration.ofMinutes(5)).toString()),
                now
        );
        notificationRepository.save(notification);
        scheduleRepository.save(NotificationSchedule.create(notification.getId(), LONG_AGO, now));
        return notification;
    }

    private ClaimedSchedule claim(Notification notification) {
        List<ClaimedSchedule> claimed = claimService.claimDueSchedules(Instant.now(), 1);
        assertThat(claimed).singleElement();
        UUID scheduleId = jdbc.queryForObject("SELECT id FROM piedrazul.notification_schedule WHERE notification_id = ?",
                UUID.class, notification.getId());
        assertThat(claimed.get(0).scheduleId()).isEqualTo(scheduleId);
        return claimed.get(0);
    }

    private NotificationDispatcherService dispatcher(NotificationProvider provider) {
        return new NotificationDispatcherService(
                store,
                new ProviderRegistry(List.of(provider)),
                new TemplateRenderer(new InMemoryNotificationTemplateProvider()),
                new RetryPolicy(),
                new ChannelFallbackPolicy(),
                new RecipientEmailPolicy(Validation.buildDefaultValidatorFactory().getValidator()),
                false,
                Clock.systemUTC()
        );
    }

    private void assertRow(Notification notification, String notificationStatus, String scheduleStatus, String attemptStatus) {
        assertThat(jdbc.queryForObject("SELECT status FROM piedrazul.notification WHERE id = ?",
                String.class, notification.getId())).isEqualTo(notificationStatus);
        assertThat(jdbc.queryForObject("SELECT status FROM piedrazul.notification_schedule WHERE notification_id = ?",
                String.class, notification.getId())).isEqualTo(scheduleStatus);
        assertThat(jdbc.queryForList("SELECT status FROM piedrazul.notification_attempts WHERE notification_id = ?",
                String.class, notification.getId())).containsExactly(attemptStatus);
    }

    private String variablesJson(Notification notification) {
        return jdbc.queryForObject("SELECT variables_json FROM piedrazul.notification WHERE id = ?",
                String.class, notification.getId());
    }

    private void assertCodeGoneFromDatabase(Notification notification) {
        String json = variablesJson(notification);
        assertThat(json)
                .doesNotContain(SYNTHETIC_CODE)
                .doesNotContain("\"" + NotificationVariables.CODE + "\"")
                .contains(NotificationVariables.EXPIRES_AT, "expirationMinutes");
    }

    private static NotificationSendResult result(AttemptStatus status, FailureType failureType) {
        return new NotificationSendResult("stub-email", NotificationChannel.EMAIL,
                status == AttemptStatus.ACCEPTED ? "stub-message" : null, status, failureType,
                failureType == null ? null : "SYNTHETIC", null);
    }

    private static final class Stub implements NotificationProvider {
        private final Supplier<NotificationSendResult> behavior;
        private int calls;

        private Stub(Supplier<NotificationSendResult> behavior) {
            this.behavior = behavior;
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
            return "stub-email";
        }

        @Override
        public NotificationSendResult send(ChannelMessage message, RecipientSnapshot recipient) {
            calls++;
            return behavior.get();
        }
    }
}
