package co.edu.unicauca.piedrazul.backend.patients;

import co.edu.unicauca.piedrazul.backend.patients.api.PatientSex;
import co.edu.unicauca.piedrazul.backend.patients.application.PatientLinkFinalizer;
import co.edu.unicauca.piedrazul.backend.patients.exception.InvalidPatientDataException;
import co.edu.unicauca.piedrazul.backend.patients.infrastructure.persistence.PatientRepository;
import co.edu.unicauca.piedrazul.backend.shared.enums.IdentificationType;
import co.edu.unicauca.piedrazul.backend.support.PostgresIntegrationSupport;
import co.edu.unicauca.piedrazul.backend.user.api.dto.internal.PersonSummary;
import co.edu.unicauca.piedrazul.backend.user.application.KeycloakUserService;
import co.edu.unicauca.piedrazul.backend.user.application.PersonExternalServiceImp;
import co.edu.unicauca.piedrazul.backend.verification.api.VerificationPurpose;
import co.edu.unicauca.piedrazul.backend.verification.api.VerifiedCode;
import co.edu.unicauca.piedrazul.backend.verification.application.VerificationAttemptProcessor;
import co.edu.unicauca.piedrazul.backend.verification.application.VerificationCodeSender;
import co.edu.unicauca.piedrazul.backend.verification.application.VerificationService;
import co.edu.unicauca.piedrazul.backend.verification.domain.VerificationCode;
import co.edu.unicauca.piedrazul.backend.verification.infrastructure.persistence.JpaVerificationCodeRepository;
import co.edu.unicauca.piedrazul.backend.verification.infrastructure.persistence.JpaVerificationCodeStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Consumir el código y registrar el paciente forman una sola unidad de trabajo.
 * Requiere transacciones y base de datos reales.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        PatientLinkFinalizer.class,
        PersonExternalServiceImp.class,
        VerificationService.class,
        VerificationAttemptProcessor.class,
        JpaVerificationCodeStore.class,
        PatientLinkAtomicityIT.TestBeans.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PatientLinkAtomicityIT extends PostgresIntegrationSupport {

    private static final String RAW_CODE = "123456";
    private static final LocalDate ADULT = LocalDate.now().minusYears(30);
    private static final LocalDate MINOR = LocalDate.now().minusYears(10);

    @TestConfiguration
    static class TestBeans {
        @Bean
        PasswordEncoder passwordEncoder() {
            return new BCryptPasswordEncoder();
        }
    }

    // El proveedor de identidad no participa en la propiedad bajo prueba.
    @MockitoBean
    private KeycloakUserService keycloakUserService;

    @MockitoBean
    private VerificationCodeSender verificationCodeSender;

    @Autowired
    private PatientLinkFinalizer finalizer;

    @Autowired
    private PersonExternalServiceImp personExternalService;

    @Autowired
    private VerificationService verificationService;

    @Autowired
    private PatientRepository patientRepository;

    @Autowired
    private JpaVerificationCodeRepository verificationCodeRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;
    private String document;
    private PersonSummary person;
    private VerifiedCode verifiedCode;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        document = String.valueOf(System.nanoTime()).substring(0, 10);

        // Persona con cuenta pero sin registro de paciente.
        person = tx.execute(status -> personExternalService.createPerson(
                IdentificationType.CEDULA, document, "Ana", "Ruiz",
                "3001234567", "ana@example.com", UUID.randomUUID()));

        verificationCodeRepository.saveAndFlush(new VerificationCode(
                document, VerificationPurpose.LINK_PATIENT_ACCOUNT,
                passwordEncoder.encode(RAW_CODE), Instant.now().plusSeconds(300), 5));

        verifiedCode = verificationService.verifyCode(
                document, VerificationPurpose.LINK_PATIENT_ACCOUNT, RAW_CODE);
    }

    private boolean codeIsUsed() {
        return verificationCodeRepository
                .findFirstBySubjectAndPurposeAndUsedFalseOrderByCreatedAtDesc(
                        document, VerificationPurpose.LINK_PATIENT_ACCOUNT)
                .isEmpty();
    }

    @Test
    void consumingAndRegisteringPatientCommitTogether() {
        finalizer.consumeOtpAndRegisterPatient(
                verifiedCode, person, PatientSex.FEMENINO, ADULT, null);

        assertTrue(codeIsUsed(), "el código debe quedar consumido");
        assertTrue(patientRepository.findById(person.id()).isPresent(), "el paciente debe existir");
    }

    // El fallo ocurre dentro del propio método productivo, después de consumir el
    // código: un menor sin teléfono de familiar hace fallar la política.
    @Test
    void aFailureAfterConsumingRollsBackBothChanges() {
        assertThrows(InvalidPatientDataException.class, () ->
                finalizer.consumeOtpAndRegisterPatient(
                        verifiedCode, person, PatientSex.FEMENINO, MINOR, null));

        assertFalse(codeIsUsed(), "el consumo debe revertirse junto con el resto");
        assertFalse(patientRepository.findById(person.id()).isPresent(),
                "el paciente no debe quedar persistido");
    }
}
