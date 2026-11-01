package co.edu.unicauca.piedrazul.backend.patients.application;

import co.edu.unicauca.piedrazul.backend.patients.api.PatientSex;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.internal.PatientData;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.internal.RegisterPatientCommand;
import co.edu.unicauca.piedrazul.backend.patients.domain.Patient;
import co.edu.unicauca.piedrazul.backend.patients.domain.Sex;
import co.edu.unicauca.piedrazul.backend.patients.exception.InvalidPatientDataException;
import co.edu.unicauca.piedrazul.backend.patients.infrastructure.persistence.PatientRepository;
import co.edu.unicauca.piedrazul.backend.shared.enums.IdentificationType;
import co.edu.unicauca.piedrazul.backend.user.PersonExternalService;
import co.edu.unicauca.piedrazul.backend.user.UserAccountProvisioningApi;
import co.edu.unicauca.piedrazul.backend.user.UserModuleApi;
import co.edu.unicauca.piedrazul.backend.user.api.dto.internal.PersonSummary;
import co.edu.unicauca.piedrazul.backend.verification.VerificationModuleApi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * El agendamiento puede registrar {@code Patient}, pero no crear cuentas ni
 * modificar accesos.
 */
@ExtendWith(MockitoExtension.class)
class PatientServiceResolveOrRegisterTest {

    private static final String DOC = "1061234567";
    private static final LocalDate ADULT = LocalDate.now().minusYears(30);

    private final UUID personId = UUID.randomUUID();

    @Mock
    private PatientRepository patientRepository;
    @Mock
    private PersonExternalService personExternalService;
    @Mock
    private UserModuleApi userModuleApi;
    @Mock
    private UserAccountProvisioningApi userAccountProvisioningApi;
    @Mock
    private VerificationModuleApi verificationModuleApi;
    @Mock
    private PatientLinkFinalizer patientLinkFinalizer;

    @InjectMocks
    private PatientService service;

    private RegisterPatientCommand command(PatientSex sex, LocalDate birthDate, String guardianPhone) {
        return new RegisterPatientCommand(
                IdentificationType.CEDULA, DOC,
                "Ana", "Ruiz", "3001234567", "ana@example.com",
                sex, birthDate, guardianPhone);
    }

    private PersonSummary canonicalPerson(UUID linkedUserId) {
        return new PersonSummary(
                personId, linkedUserId, IdentificationType.CEDULA, DOC,
                "Ana Canónica", "Ruiz Canónica", "3009998888", "canonico@example.com");
    }

    @Test
    void shouldCreatePersonAndPatientWhenNothingExists() {
        when(personExternalService.findByIdentification(DOC)).thenReturn(Optional.empty());
        when(personExternalService.createPerson(
                any(), anyString(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(canonicalPerson(null));
        when(patientRepository.save(any(Patient.class))).thenAnswer(call -> call.getArgument(0));

        PatientData result = service.resolveOrRegisterPatient(command(PatientSex.FEMENINO, ADULT, null));

        assertEquals(personId, result.personId());
        assertNull(result.userId());
        verifyNoInteractions(userModuleApi, userAccountProvisioningApi);
    }

    @Test
    void shouldOnlyCreatePatientWhenPersonAlreadyExistsAndKeepCanonicalData() {
        when(personExternalService.findByIdentification(DOC))
                .thenReturn(Optional.of(canonicalPerson(UUID.randomUUID())));
        when(patientRepository.findById(personId)).thenReturn(Optional.empty());
        when(patientRepository.save(any(Patient.class))).thenAnswer(call -> call.getArgument(0));

        PatientData result = service.resolveOrRegisterPatient(command(PatientSex.FEMENINO, ADULT, null));

        assertEquals("Ana Canónica", result.firstName());
        assertEquals("3009998888", result.phone());
        verify(personExternalService, never())
                .createPerson(any(), anyString(), anyString(), anyString(), anyString(), anyString(), any());
        verifyNoInteractions(userModuleApi, userAccountProvisioningApi);
    }

    @Test
    void shouldReuseExistingPatientWithoutOverwritingIt() {
        Patient existing = new Patient(personId, Sex.MASCULINO, ADULT, "3001112222");

        when(personExternalService.findByIdentification(DOC))
                .thenReturn(Optional.of(canonicalPerson(null)));
        when(patientRepository.findById(personId)).thenReturn(Optional.of(existing));

        PatientData result = service.resolveOrRegisterPatient(
                command(PatientSex.FEMENINO, LocalDate.now().minusYears(40), "3005556666"));

        assertEquals(PatientSex.MASCULINO, result.sex());
        assertEquals("3001112222", result.guardianPhone());
        verify(patientRepository, never()).save(any());
        verifyNoInteractions(userModuleApi, userAccountProvisioningApi);
    }

    @Test
    void shouldKeepApplyingPatientPolicyWhenRegistering() {
        when(personExternalService.findByIdentification(DOC)).thenReturn(Optional.empty());
        when(personExternalService.createPerson(
                any(), anyString(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(canonicalPerson(null));

        assertThrows(InvalidPatientDataException.class, () -> service.resolveOrRegisterPatient(
                command(PatientSex.FEMENINO, LocalDate.now().minusYears(10), null)));

        verify(patientRepository, never()).save(any());
    }
}
