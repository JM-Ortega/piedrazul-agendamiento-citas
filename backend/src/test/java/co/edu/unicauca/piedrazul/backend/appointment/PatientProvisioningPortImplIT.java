package co.edu.unicauca.piedrazul.backend.appointment;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.DocumentType;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Gender;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.PatientRegistrationData;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.PatientSnapshot;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.PatientProvisioningPort;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.integration.PatientProvisioningPortImpl;
import co.edu.unicauca.piedrazul.backend.patients.PatientModuleApi;
import co.edu.unicauca.piedrazul.backend.patients.api.PatientSex;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.internal.PatientData;
import co.edu.unicauca.piedrazul.backend.patients.application.PatientLinkFinalizer;
import co.edu.unicauca.piedrazul.backend.patients.application.PatientService;
import co.edu.unicauca.piedrazul.backend.shared.enums.IdentificationType;
import co.edu.unicauca.piedrazul.backend.support.PostgresIntegrationSupport;
import co.edu.unicauca.piedrazul.backend.user.UserAccountProvisioningApi;
import co.edu.unicauca.piedrazul.backend.user.application.KeycloakUserService;
import co.edu.unicauca.piedrazul.backend.user.application.PersonExternalServiceImp;
import co.edu.unicauca.piedrazul.backend.verification.application.VerificationAttemptProcessor;
import co.edu.unicauca.piedrazul.backend.verification.application.VerificationCodeSender;
import co.edu.unicauca.piedrazul.backend.verification.application.VerificationService;
import co.edu.unicauca.piedrazul.backend.verification.infrastructure.persistence.JpaVerificationCodeStore;
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

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integración real de {@link PatientProvisioningPortImpl} contra el módulo patients: el
 * registro efectivo de un paciente nuevo, la conservación de datos canónicos cuando el
 * documento ya existe, y el caso de negocio real de {@code Gender.OTRO} (ya no soportado).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        PatientProvisioningPortImpl.class,
        PatientService.class,
        PatientLinkFinalizer.class,
        PersonExternalServiceImp.class,
        VerificationService.class,
        VerificationAttemptProcessor.class,
        JpaVerificationCodeStore.class,
        PatientProvisioningPortImplIT.TestBeans.class
})
class PatientProvisioningPortImplIT extends PostgresIntegrationSupport {

    @TestConfiguration
    static class TestBeans {
        @Bean
        PasswordEncoder passwordEncoder() {
            return new BCryptPasswordEncoder();
        }
    }

    @MockitoBean
    private KeycloakUserService keycloakUserService;
    @MockitoBean
    private UserAccountProvisioningApi userAccountProvisioningApi;
    @MockitoBean
    private VerificationCodeSender verificationCodeSender;

    @Autowired
    private PatientProvisioningPort patientProvisioningPort;
    @Autowired
    private PatientModuleApi patientModuleApi;

    private final AtomicLong documentSequence = new AtomicLong(System.nanoTime());

    private String uniqueDocument() {
        return String.valueOf(1_000_000_000L + (Math.abs(documentSequence.incrementAndGet()) % 900_000_000L));
    }

    private PatientRegistrationData formData(String document, String firstName, String lastName) {
        return new PatientRegistrationData(
                DocumentType.CEDULA, document, firstName, lastName, "3001234567",
                firstName.toLowerCase() + "@example.com", Gender.FEMENINO, LocalDate.of(1990, 6, 15), null);
    }

    @Test
    void resolveOrRegisterShouldCreateAndReturnANewPatientWhenDocumentDoesNotExist() {
        String document = uniqueDocument();

        PatientSnapshot snapshot = patientProvisioningPort.resolveOrRegister(formData(document, "Ana", "Ruiz"));

        assertThat(snapshot.patientInfo().getFirstName()).isEqualTo("Ana");
        assertThat(snapshot.patientInfo().getLastName()).isEqualTo("Ruiz");
        assertThat(patientModuleApi.existsById(snapshot.idPatient())).isTrue();
    }

    @Test
    void resolveOrRegisterShouldKeepCanonicalDataWhenDocumentAlreadyExists() {
        String document = uniqueDocument();
        PatientData canonical = patientModuleApi.createPatient(
                IdentificationType.CEDULA, document, "Ana Canonica", "Ruiz Canonica", "3009998888",
                "canonico@example.com", null, PatientSex.FEMENINO, LocalDate.of(1990, 6, 15), null);

        // El formulario trae datos distintos para el mismo documento
        PatientSnapshot snapshot = patientProvisioningPort.resolveOrRegister(
                formData(document, "Ana Formulario", "Ruiz Formulario"));

        assertThat(snapshot.idPatient()).isEqualTo(canonical.personId());
        assertThat(snapshot.patientInfo().getFirstName()).isEqualTo("Ana Canonica");
        assertThat(snapshot.patientInfo().getPhone()).isEqualTo("3009998888");
    }

    @Test
    void resolveOrRegisterShouldThrowWhenGenderIsOtro() {
        PatientRegistrationData data = new PatientRegistrationData(
                DocumentType.CEDULA, uniqueDocument(), "Ana", "Ruiz", "3001234567",
                "ana@example.com", Gender.OTRO, LocalDate.of(1990, 6, 15), null);

        assertThatThrownBy(() -> patientProvisioningPort.resolveOrRegister(data))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
