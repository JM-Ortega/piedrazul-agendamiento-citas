package co.edu.unicauca.piedrazul.backend.user.application;

import co.edu.unicauca.piedrazul.backend.doctors.DoctorProvisioningApi;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.internal.CreateDoctorRequest;
import co.edu.unicauca.piedrazul.backend.doctors.domain.DocumentType;
import co.edu.unicauca.piedrazul.backend.patients.PatientModuleApi;
import co.edu.unicauca.piedrazul.backend.patients.api.PatientSex;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.internal.CreatePatientUserRequest;
import co.edu.unicauca.piedrazul.backend.shared.enums.IdentificationType;
import co.edu.unicauca.piedrazul.backend.shared.enums.Role;
import co.edu.unicauca.piedrazul.backend.shared.enums.SpecialtyCode;
import co.edu.unicauca.piedrazul.backend.user.PersonExternalService;
import co.edu.unicauca.piedrazul.backend.user.api.dto.input.CreateSystemUserPayload;
import co.edu.unicauca.piedrazul.backend.user.api.dto.input.CreateSystemUserRequest;
import co.edu.unicauca.piedrazul.backend.user.api.dto.internal.PersonSummary;
import co.edu.unicauca.piedrazul.backend.user.api.dto.internal.UserSummary;
import co.edu.unicauca.piedrazul.backend.user.exception.InvalidUserDataException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CreateAccountUseCaseTest {

    @Mock
    private DoctorProvisioningApi doctorProvisioningApi;

    @Mock
    private PatientModuleApi patientModuleApi;

    @Mock
    private PersonExternalService personExternalService;

    @Mock
    private KeycloakUserProvisioningService keycloakUserProvisioningService;

    @InjectMocks
    private CreateAccountUseCase createAccountUseCase;

    @Test
    void executeShouldCreateDoctorAndPatientWhenRolesIncludeBoth() {
        UUID personId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID userId = UUID.fromString("22222222-2222-2222-2222-222222222222");

        CreateSystemUserPayload payload = buildPayload(List.of(Role.DOCTOR, Role.PATIENT));
        UserSummary createdUser = new UserSummary(userId, "doctor-patient", "Ana", "Perez", "ana@test.com", List.of(Role.DOCTOR.name(), Role.PATIENT.name()));
        PersonSummary personSummary = new PersonSummary(
                personId,
                userId,
                IdentificationType.CEDULA,
                "1001",
                "Ana",
                "Perez",
                "3206228173",
                "ana@test.com"
        );

        when(keycloakUserProvisioningService.getOrCreateUser(payload.user(), List.of(Role.DOCTOR, Role.PATIENT)))
                .thenReturn(createdUser);
        when(personExternalService.createPerson(
                payload.user().identificationType(),
                payload.user().identification(),
                createdUser.firstName(),
                createdUser.lastName(),
                payload.user().phone(),
                createdUser.email(),
                createdUser.id()
        )).thenReturn(personSummary);

        createAccountUseCase.execute(payload);

        verify(keycloakUserProvisioningService).getOrCreateUser(payload.user(), List.of(Role.DOCTOR, Role.PATIENT));
        verify(personExternalService).createPerson(
                payload.user().identificationType(),
                payload.user().identification(),
                createdUser.firstName(),
                createdUser.lastName(),
                payload.user().phone(),
                createdUser.email(),
                createdUser.id()
        );
        verify(doctorProvisioningApi).createDoctor(personId, payload.doctor());
        verify(patientModuleApi).createPatientForExistingPerson(
                personId,
                payload.patient().sex(),
                payload.patient().birthDate(),
                payload.patient().guardianPhone()
        );
    }

    @Test
    void executeShouldThrowWhenRolesAreMissing() {
        CreateSystemUserPayload payload = buildPayload(List.of());

        assertThrows(InvalidUserDataException.class, () -> createAccountUseCase.execute(payload));
    }

    @Test
    void executeShouldThrowWhenRolesCombinationIsInvalid() {
        CreateSystemUserPayload payload = buildPayload(List.of(Role.ADMIN, Role.PATIENT));

        assertThrows(InvalidUserDataException.class, () -> createAccountUseCase.execute(payload));
    }

    private CreateSystemUserPayload buildPayload(List<Role> roles) {
        CreateSystemUserRequest userRequest = new CreateSystemUserRequest(
                "1001",
                IdentificationType.CEDULA,
                "Ana",
                "Perez",
                "ana@example.com",
                "3206228173",
                "secret123"
        );

        CreateDoctorRequest doctorRequest = new CreateDoctorRequest(
                DocumentType.CEDULA,
                "3206228173",
                List.of(SpecialtyCode.MEDICINA_GENERAL),
                LocalDate.of(2024, 1, 1),
                LocalDate.of(2024, 12, 31),
                30,
                4,
                List.of()
        );

        CreatePatientUserRequest patientRequest = new CreatePatientUserRequest(
                PatientSex.FEMENINO,
                LocalDate.of(1990, 1, 1),
                "3001234567"
        );

        return new CreateSystemUserPayload(userRequest, doctorRequest, patientRequest, roles);
    }
}
