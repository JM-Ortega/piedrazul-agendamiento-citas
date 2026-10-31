package co.edu.unicauca.piedrazul.backend.patients.application;

import co.edu.unicauca.piedrazul.backend.patients.api.PatientSex;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.internal.PatientData;
import co.edu.unicauca.piedrazul.backend.patients.domain.Patient;
import co.edu.unicauca.piedrazul.backend.patients.exception.InvalidPatientDataException;
import co.edu.unicauca.piedrazul.backend.patients.infrastructure.persistence.PatientRepository;
import co.edu.unicauca.piedrazul.backend.shared.enums.IdentificationType;
import co.edu.unicauca.piedrazul.backend.shared.enums.Role;
import co.edu.unicauca.piedrazul.backend.user.PersonExternalService;
import co.edu.unicauca.piedrazul.backend.user.UserAccountProvisioningApi;
import co.edu.unicauca.piedrazul.backend.user.UserModuleApi;
import co.edu.unicauca.piedrazul.backend.user.api.dto.internal.PersonSummary;
import co.edu.unicauca.piedrazul.backend.user.api.dto.internal.UserSummary;
import co.edu.unicauca.piedrazul.backend.user.exception.PersonAlreadyExistsException;
import co.edu.unicauca.piedrazul.backend.user.exception.UserAlreadyExistsException;
import co.edu.unicauca.piedrazul.backend.verification.VerificationModuleApi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Autoregistro público: solo debe dar de alta identidades nuevas y
 * nunca adoptar una cuenta existente.
 */
@ExtendWith(MockitoExtension.class)
class PatientServiceRegistrationTest {

    private static final String DOC = "1061234567";
    private static final LocalDate ADULT = LocalDate.now().minusYears(30);

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

    private PatientData register() {
        return service.createPatientWithUser(
                DOC, "Secreta123", IdentificationType.CEDULA, DOC,
                "Ana", "Ruiz", "3001234567", "ana@example.com",
                PatientSex.FEMENINO, ADULT, null
        );
    }

    private PersonSummary person(UUID personId, UUID userId) {
        return new PersonSummary(
                personId, userId, IdentificationType.CEDULA, DOC,
                "Ana", "Ruiz", "3001234567", "ana@example.com");
    }

    @Test
    void shouldNotAdoptAnExistingAccount() {
        // Sin persona previa, la autoridad es la creación estricta: el proveedor de
        // identidad responde conflicto y el caso de uso no adopta la cuenta.
        doNothing().when(personExternalService).requireIdentificationAvailable(DOC);
        when(userAccountProvisioningApi.createAccount(any(), anyList()))
                .thenThrow(new UserAlreadyExistsException());

        assertThrows(UserAlreadyExistsException.class, this::register);

        verify(userModuleApi, never()).ensurePatientRole(any());
        verify(personExternalService, never())
                .createPerson(any(), anyString(), anyString(), anyString(), anyString(), anyString(), any());
        verify(patientRepository, never()).save(any());
    }

    @Test
    void shouldRejectBeforeTouchingKeycloakWhenPersonAlreadyExists() {
        doThrow(new PersonAlreadyExistsException("ya existe"))
                .when(personExternalService).requireIdentificationAvailable(DOC);

        assertThrows(PersonAlreadyExistsException.class, this::register);

        verify(userAccountProvisioningApi, never()).createAccount(any(), anyList());
        verify(userModuleApi, never()).ensurePatientRole(any());
    }

    @Test
    void shouldRegisterBrandNewIdentity() {
        UUID personId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        doNothing().when(personExternalService).requireIdentificationAvailable(DOC);
        when(userAccountProvisioningApi.createAccount(any(), eq(List.of(Role.PATIENT))))
                .thenReturn(new UserSummary(userId, DOC, "Ana", "Ruiz", "ana@example.com", List.of()));
        when(personExternalService.createPerson(
                any(), anyString(), anyString(), anyString(), anyString(), anyString(), eq(userId)))
                .thenReturn(person(personId, userId));
        when(patientRepository.save(any(Patient.class))).thenAnswer(call -> call.getArgument(0));

        PatientData result = register();

        assertEquals(personId, result.personId());
        assertEquals(userId, result.userId());
        verify(userAccountProvisioningApi).createAccount(any(), anyList());
    }

    /**
     * Los datos de paciente inválidos deben rechazarse antes de cualquier efecto
     * externo: ni cuenta, ni persona, ni paciente.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidPatientData")
    void shouldRejectInvalidPatientDataBeforeAnySideEffect(
            String caseName, PatientSex sex, LocalDate birthDate, String guardianPhone) {

        assertThrows(InvalidPatientDataException.class, () -> service.createPatientWithUser(
                DOC, "Secreta123", IdentificationType.CEDULA, DOC,
                "Ana", "Ruiz", "3001234567", "ana@example.com",
                sex, birthDate, guardianPhone
        ));

        verify(userAccountProvisioningApi, never()).createAccount(any(), anyList());
        verify(personExternalService, never())
                .createPerson(any(), anyString(), anyString(), anyString(), anyString(), anyString(), any());
        verify(patientRepository, never()).save(any());
        verify(userModuleApi, never()).ensurePatientRole(any());
    }

    static Stream<Arguments> invalidPatientData() {
        return Stream.of(
                Arguments.of("sexo ausente", null, ADULT, null),
                Arguments.of("fecha de nacimiento ausente", PatientSex.FEMENINO, null, null),
                Arguments.of("menor sin teléfono de familiar",
                        PatientSex.FEMENINO, LocalDate.now().minusYears(10), null),
                Arguments.of("fecha de nacimiento futura",
                        PatientSex.FEMENINO, LocalDate.now().plusDays(1), null),
                Arguments.of("cédula incompatible con la edad",
                        PatientSex.FEMENINO, LocalDate.now().minusYears(10), "3001234567")
        );
    }
}
