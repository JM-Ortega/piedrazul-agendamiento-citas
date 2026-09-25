package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.integration;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.PatientInfo;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.PatientConsultPort;
import co.edu.unicauca.piedrazul.backend.appointment.exception.AppointmentPatientNotFoundException;
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
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integración real de {@link PatientConsultPortImpl} contra el módulo patients (mismos
 * colaboradores reales que ya cablea {@code ManualSchedulingRollbackIT}).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        PatientConsultPortImpl.class,
        PatientService.class,
        PatientLinkFinalizer.class,
        PersonExternalServiceImp.class,
        VerificationService.class,
        VerificationAttemptProcessor.class,
        JpaVerificationCodeStore.class,
        PatientConsultPortImplIT.TestBeans.class
})
class PatientConsultPortImplIT extends PostgresIntegrationSupport {

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
    private PatientConsultPort patientConsultPort;
    @Autowired
    private PatientModuleApi patientModuleApi;

    private final AtomicLong documentSequence = new AtomicLong(System.nanoTime());

    private String uniqueDocument() {
        return String.valueOf(1_000_000_000L + (Math.abs(documentSequence.incrementAndGet()) % 900_000_000L));
    }

    private PatientData newPatient(String firstName, String lastName) {
        return patientModuleApi.createPatient(
                IdentificationType.CEDULA, uniqueDocument(), firstName, lastName, "3001234567",
                firstName.toLowerCase() + "@example.com", null, PatientSex.FEMENINO,
                LocalDate.of(1990, 6, 15), null);
    }

    @Test
    void findByIdShouldReturnPatientInfoWhenPatientExists() {
        PatientData patient = newPatient("Ana", "Ruiz");

        PatientInfo result = patientConsultPort.findById(patient.personId());

        assertThat(result.getFirstName()).isEqualTo("Ana");
        assertThat(result.getLastName()).isEqualTo("Ruiz");
        assertThat(result.getPhone()).isEqualTo("3001234567");
    }

    @Test
    void findByIdShouldThrowWhenPatientDoesNotExist() {
        assertThatThrownBy(() -> patientConsultPort.findById(UUID.randomUUID()))
                .isInstanceOf(AppointmentPatientNotFoundException.class);
    }

    @Test
    void findByIdsShouldReturnAllRequestedPatients() {
        PatientData first = newPatient("Ana", "Ruiz");
        PatientData second = newPatient("Carlos", "Gomez");

        Map<UUID, PatientInfo> result = patientConsultPort.findByIds(Set.of(first.personId(), second.personId()));

        assertThat(result).hasSize(2);
        assertThat(result.get(first.personId()).getFirstName()).isEqualTo("Ana");
        assertThat(result.get(second.personId()).getFirstName()).isEqualTo("Carlos");
    }

    @Test
    void existsByIdShouldReturnTrueWhenPatientExists() {
        PatientData patient = newPatient("Ana", "Ruiz");

        assertThat(patientConsultPort.existsById(patient.personId())).isTrue();
    }

    @Test
    void existsByIdShouldReturnFalseWhenPatientDoesNotExist() {
        assertThat(patientConsultPort.existsById(UUID.randomUUID())).isFalse();
    }
}
