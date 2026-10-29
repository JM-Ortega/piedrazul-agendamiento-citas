package co.edu.unicauca.piedrazul.backend.patients;

import co.edu.unicauca.piedrazul.backend.notifications.api.OtpDeliveryMode;
import co.edu.unicauca.piedrazul.backend.notifications.application.OtpDeliveryCapability;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.config.NotificationConfig;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.mappers.NotificationAttemptMapper;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.mappers.NotificationMapper;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.mappers.NotificationScheduleMapper;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.persistence.adapter.NotificationAttemptRepositoryAdapter;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.persistence.adapter.NotificationRepositoryAdapter;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.persistence.adapter.NotificationScheduleRepositoryAdapter;
import co.edu.unicauca.piedrazul.backend.patients.application.PatientLinkFinalizer;
import co.edu.unicauca.piedrazul.backend.patients.application.PatientService;
import co.edu.unicauca.piedrazul.backend.shared.enums.IdentificationType;
import co.edu.unicauca.piedrazul.backend.support.PostgresIntegrationSupport;
import co.edu.unicauca.piedrazul.backend.user.UserAccountProvisioningApi;
import co.edu.unicauca.piedrazul.backend.user.api.dto.internal.PersonSummary;
import co.edu.unicauca.piedrazul.backend.user.application.KeycloakUserService;
import co.edu.unicauca.piedrazul.backend.user.application.PersonExternalServiceImp;
import co.edu.unicauca.piedrazul.backend.verification.api.VerificationPurpose;
import co.edu.unicauca.piedrazul.backend.verification.application.VerificationAttemptProcessor;
import co.edu.unicauca.piedrazul.backend.verification.application.VerificationService;
import co.edu.unicauca.piedrazul.backend.verification.domain.VerificationCode;
import co.edu.unicauca.piedrazul.backend.verification.exception.VerificationContactUnusableException;
import co.edu.unicauca.piedrazul.backend.verification.exception.VerificationDeliveryUnavailableException;
import co.edu.unicauca.piedrazul.backend.verification.infrastructure.delivery.NotificationVerificationCodeSender;
import co.edu.unicauca.piedrazul.backend.verification.infrastructure.persistence.JpaVerificationCodeRepository;
import co.edu.unicauca.piedrazul.backend.verification.infrastructure.persistence.JpaVerificationCodeStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doReturn;

/**
 * La comprobación previa a emitir un código de vinculación sobre PostgreSQL real, recorriendo
 * Patients → Verification → Notifications con la persistencia real de notificaciones (sin
 * simular el emisor). Se comparan las filas de códigos, notificaciones y programaciones antes y
 * después de cada solicitud.
 *
 * <p>Con la configuración por defecto (solo consola, sin opt-in) la capacidad real resuelta es
 * {@link OtpDeliveryMode#UNAVAILABLE}; los demás modos se fijan sobre el mismo bean espía, que
 * sigue validando destinatarios con su lógica real.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        PatientService.class,
        PatientLinkFinalizer.class,
        PersonExternalServiceImp.class,
        VerificationService.class,
        VerificationAttemptProcessor.class,
        JpaVerificationCodeStore.class,
        NotificationVerificationCodeSender.class,
        NotificationConfig.class,
        NotificationRepositoryAdapter.class,
        NotificationScheduleRepositoryAdapter.class,
        NotificationAttemptRepositoryAdapter.class,
        NotificationMapper.class,
        NotificationScheduleMapper.class,
        NotificationAttemptMapper.class,
        LinkCodeIssuancePreflightIT.TestBeans.class
})
// Sin transacción envolvente: cada solicitud confirma o revierte como en producción.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class LinkCodeIssuancePreflightIT extends PostgresIntegrationSupport {

    private static final VerificationPurpose PURPOSE = VerificationPurpose.LINK_PATIENT_ACCOUNT;

    @TestConfiguration
    static class TestBeans {
        @Bean
        PasswordEncoder passwordEncoder() {
            return new BCryptPasswordEncoder();
        }

        @Bean
        LocalValidatorFactoryBean validator() {
            return new LocalValidatorFactoryBean();
        }
    }

    // El proveedor de identidad no participa en la propiedad bajo prueba (también hace de
    // UserModuleApi).
    @MockitoBean
    private KeycloakUserService keycloakUserService;

    @MockitoBean
    private UserAccountProvisioningApi userAccountProvisioningApi;

    @MockitoSpyBean
    private OtpDeliveryCapability otpDeliveryCapability;

    @Autowired
    private PatientService patientService;

    @Autowired
    private PersonExternalServiceImp personExternalService;

    @Autowired
    private JpaVerificationCodeRepository verificationCodeRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
    }

    /** Persona sintética con cuenta y sin registro de paciente: puede pedir código. */
    private PersonSummary eligiblePerson(String phone, String email) {
        String document = String.valueOf(System.nanoTime()).substring(0, 10);
        return tx.execute(status -> personExternalService.createPerson(
                IdentificationType.CEDULA, document, "Ana", "Ruiz", phone, email, UUID.randomUUID()));
    }

    /** Un código activo emitido hace dos minutos (fuera de la espera de un minuto). */
    private UUID seedActiveCode(String subject) {
        VerificationCode code = new VerificationCode(subject, PURPOSE,
                passwordEncoder.encode("123456"), Instant.now().plusSeconds(180), 5);
        UUID id = verificationCodeRepository.saveAndFlush(code).getId();
        jdbc.update("UPDATE piedrazul.verification_code SET created_at = now() - interval '2 minutes' WHERE id = ?", id);
        return id;
    }

    private void givenMode(OtpDeliveryMode mode) {
        doReturn(mode).when(otpDeliveryCapability).mode();
    }

    private record Snapshot(List<Map<String, Object>> codes, long notifications, long schedules, long attempts) {
    }

    private Snapshot snapshot(String subject) {
        return new Snapshot(
                jdbc.queryForList("""
                        SELECT id, code_hash, attempts, used, created_at, expires_at
                        FROM piedrazul.verification_code WHERE subject = ? ORDER BY created_at""", subject),
                count("piedrazul.notification"),
                count("piedrazul.notification_schedule"),
                count("piedrazul.notification_attempts"));
    }

    private long count(String table) {
        Long value = jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
        return value == null ? 0 : value;
    }

    private void assertActiveCodeUntouched(String subject, UUID activeId) {
        List<Map<String, Object>> codes = snapshot(subject).codes();
        assertEquals(1, codes.size());
        assertEquals(activeId, codes.getFirst().get("id"));
        assertEquals(false, codes.getFirst().get("used"));
    }

    // ── Sin canal: 503, sin ningún cambio ────────────────────────────────

    @Test
    void theRealDefaultConfigurationIsUnavailable() {
        assertThat(otpDeliveryCapability.mode()).isEqualTo(OtpDeliveryMode.UNAVAILABLE);
    }

    @Test
    void withoutAChannelNothingChangesForAKnownDocument() {
        PersonSummary person = eligiblePerson("3001234567", "ana.ruiz@example.com");
        UUID activeId = seedActiveCode(person.identification());
        Snapshot before = snapshot(person.identification());

        assertThrows(VerificationDeliveryUnavailableException.class,
                () -> patientService.requestLinkUserAccountCode(person.identification()));

        assertEquals(before, snapshot(person.identification()));
        assertActiveCodeUntouched(person.identification(), activeId);
    }

    @Test
    void withoutAChannelAnUnknownDocumentGetsTheSameRejection() {
        String unknown = "doc-" + UUID.randomUUID();
        Snapshot before = snapshot(unknown);

        assertThrows(VerificationDeliveryUnavailableException.class,
                () -> patientService.requestLinkUserAccountCode(unknown));

        assertEquals(before, snapshot(unknown));
    }

    // ── Correo apto: contacto inutilizable → 409, sin cambios ────────────

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "ana.ruiz", "ana.ruiz@example.invalid", "ana@SUB.Example.INVALID", "ana@example.invalid."})
    void anUnusableEmailChangesNothing(String email) {
        givenMode(OtpDeliveryMode.EMAIL);
        PersonSummary person = eligiblePerson("3001234567", email);
        UUID activeId = seedActiveCode(person.identification());
        Snapshot before = snapshot(person.identification());

        assertThrows(VerificationContactUnusableException.class,
                () -> patientService.requestLinkUserAccountCode(person.identification()));

        assertEquals(before, snapshot(person.identification()));
        assertActiveCodeUntouched(person.identification(), activeId);
    }

    @Test
    void aPhoneWithoutEmailIsNotUsableYet() {
        givenMode(OtpDeliveryMode.EMAIL);
        PersonSummary person = eligiblePerson("3001234567", null);
        Snapshot before = snapshot(person.identification());

        assertThrows(VerificationContactUnusableException.class,
                () -> patientService.requestLinkUserAccountCode(person.identification()));

        assertEquals(before, snapshot(person.identification()));
    }

    // ── Correo apto y contacto válido: se emite y se encola ──────────────

    @Test
    void aValidEmailIssuesACodeAndEnqueuesExactlyOneNotification() {
        givenMode(OtpDeliveryMode.EMAIL);
        PersonSummary person = eligiblePerson("3001234567", "ana.ruiz@example.com");
        UUID previousId = seedActiveCode(person.identification());
        Snapshot before = snapshot(person.identification());

        patientService.requestLinkUserAccountCode(person.identification());

        Snapshot after = snapshot(person.identification());
        assertEquals(2, after.codes().size());
        assertEquals(previousId, after.codes().getFirst().get("id"));
        assertEquals(true, after.codes().getFirst().get("used"), "el código anterior queda invalidado");
        assertEquals(false, after.codes().get(1).get("used"));
        assertThat((String) after.codes().get(1).get("code_hash")).startsWith("$2");
        assertEquals(before.notifications() + 1, after.notifications());
        assertEquals(before.schedules() + 1, after.schedules());
        // Encolar no es entregar: todavía no hay ningún intento de envío.
        assertEquals(before.attempts(), after.attempts());

        UUID newCodeId = (UUID) after.codes().get(1).get("id");
        Map<String, Object> notification = jdbc.queryForMap("""
                SELECT type_code, status, recipient_id, recipient_email
                FROM piedrazul.notification WHERE aggregate_id = ?""", newCodeId);
        assertEquals("OTP_CODE", notification.get("type_code"));
        assertEquals(person.id(), notification.get("recipient_id"));
        assertEquals("ana.ruiz@example.com", notification.get("recipient_email"));
        assertThat(String.valueOf(notification.get("status"))).isNotEqualTo("SENT");
    }

    @Test
    void anImmediateSecondRequestIsStillLimitedAndChangesNothing() {
        givenMode(OtpDeliveryMode.EMAIL);
        PersonSummary person = eligiblePerson("3001234567", "ana.ruiz@example.com");
        patientService.requestLinkUserAccountCode(person.identification());
        Snapshot before = snapshot(person.identification());

        assertThrows(
                co.edu.unicauca.piedrazul.backend.verification.exception.VerificationCodeRequestLimitException.class,
                () -> patientService.requestLinkUserAccountCode(person.identification()));

        assertEquals(before, snapshot(person.identification()));
    }

    // ── Diagnóstico por consola: destinatario ficticio ───────────────────

    @Test
    void theDiagnosticModeAcceptsAFictionalRecipientAndOnlyEnqueues() {
        givenMode(OtpDeliveryMode.DIAGNOSTIC_CONSOLE);
        PersonSummary person = eligiblePerson("3000000000", "ana.ruiz@example.invalid");
        Snapshot before = snapshot(person.identification());

        patientService.requestLinkUserAccountCode(person.identification());

        Snapshot after = snapshot(person.identification());
        assertEquals(1, after.codes().size());
        assertEquals(before.notifications() + 1, after.notifications());
        assertEquals(before.schedules() + 1, after.schedules());
        assertEquals(before.attempts(), after.attempts());
    }
}
