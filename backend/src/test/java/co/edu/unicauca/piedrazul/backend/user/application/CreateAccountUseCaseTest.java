package co.edu.unicauca.piedrazul.backend.user.application;

import co.edu.unicauca.piedrazul.backend.doctors.DoctorProvisioningApi;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.internal.CreateDoctorRequest;
import co.edu.unicauca.piedrazul.backend.doctors.domain.DocumentType;
import co.edu.unicauca.piedrazul.backend.doctors.domain.SpecialtyCode;
import co.edu.unicauca.piedrazul.backend.patients.PatientModuleApi;

//import co.edu.unicauca.piedrazul.backend.patients.api.PatientDocumentType;
//import co.edu.unicauca.piedrazul.backend.patients.api.PatientGender;

import co.edu.unicauca.piedrazul.backend.patients.api.dto.internal.CreatePatientUserRequest;
import co.edu.unicauca.piedrazul.backend.shared.enums.Role;
import co.edu.unicauca.piedrazul.backend.user.api.dto.input.CreateSystemUserPayload;
import co.edu.unicauca.piedrazul.backend.user.api.dto.input.CreateSystemUserRequest;
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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


@ExtendWith(MockitoExtension.class)
class CreateAccountUseCaseTest {
/*
	@Mock
	private KeycloakUserProvisioningService keycloakUserProvisioningService;

    @Mock
    private DoctorProvisioningApi doctorProvisioningApi;

    @Mock
    private PatientModuleApi patientModuleApi;

    @InjectMocks
    private CreateAccountUseCase createAccountUseCase;

    @Test
    void executeShouldRejectNullPayload() {
	assertThrows(InvalidUserDataException.class, () -> createAccountUseCase.execute(null));

	verify(keycloakUserProvisioningService, never()).getOrCreateUser(any(co.edu.unicauca.piedrazul.backend.user.api.dto.input.CreateSystemUserRequest.class), any(java.util.List.class));
    }

    @Test
    void executeShouldRejectMissingUserData() {
	CreateSystemUserPayload payload = new CreateSystemUserPayload(
		null,
		null,
		null,
		List.of(Role.DOCTOR)
	);

	assertThrows(InvalidUserDataException.class, () -> createAccountUseCase.execute(payload));

	verify(keycloakUserProvisioningService, never()).getOrCreateUser(any(co.edu.unicauca.piedrazul.backend.user.api.dto.input.CreateSystemUserRequest.class), any(java.util.List.class));
    }

    @Test
    void executeShouldRejectEmptyRoles() {
	CreateSystemUserPayload payload = new CreateSystemUserPayload(
		buildUserRequest(),
		null,
		null,
		List.of()
	);

	assertThrows(InvalidUserDataException.class, () -> createAccountUseCase.execute(payload));

	verify(keycloakUserProvisioningService, never()).getOrCreateUser(any(co.edu.unicauca.piedrazul.backend.user.api.dto.input.CreateSystemUserRequest.class), any(java.util.List.class));
    }

    @Test
    void executeShouldCreateDoctorWhenDoctorRoleIsPresent() {
	UUID userId = UUID.fromString("11111111-1111-1111-1111-111111111111");
	UserSummary createdUser = new UserSummary(userId, "doctor01", "Ana", "Lopez", "ana@test.com");
	CreateDoctorRequest doctorRequest = new CreateDoctorRequest(
		DocumentType.CEDULA,
		"3001234567",
		List.of("MEDICINA_GENERAL"),
		LocalDate.of(2024, 1, 1),
		null,
		30,
		1,
			null
	);
	CreateSystemUserPayload payload = new CreateSystemUserPayload(
		buildUserRequest(),
		doctorRequest,
		null,
		List.of(Role.DOCTOR)
	);

	when(keycloakUserProvisioningService.getOrCreateUser(any(co.edu.unicauca.piedrazul.backend.user.api.dto.input.CreateSystemUserRequest.class), any(java.util.List.class))).thenReturn(createdUser);

	createAccountUseCase.execute(payload);

	verify(keycloakUserProvisioningService).getOrCreateUser(eq(payload.user()), anyList());
	verify(doctorProvisioningApi).createDoctor(
		userId,
		"Ana",
		"Lopez",
		"doctor01",
		doctorRequest
	);
		verify(patientModuleApi, never()).createPatient(any(), any(), any(), any(), any(), any());
    }

    @Test
    void executeShouldCreatePatientWhenPatientRoleIsPresent() {
	UUID userId = UUID.fromString("22222222-2222-2222-2222-222222222222");
	UserSummary createdUser = new UserSummary(userId, "patient01", "Luis", "Perez", "luis@test.com");
	CreatePatientUserRequest patientRequest = new CreatePatientUserRequest(
		PatientDocumentType.CEDULA,
		"3007654321",
		PatientGender.MASCULINO,
		LocalDate.of(2000, 6, 15),
		"3001112233"
	);
	CreateSystemUserPayload payload = new CreateSystemUserPayload(
		buildUserRequest(),
		null,
		patientRequest,
		List.of(Role.PATIENT)
	);

	when(keycloakUserProvisioningService.getOrCreateUser(any(co.edu.unicauca.piedrazul.backend.user.api.dto.input.CreateSystemUserRequest.class), any(java.util.List.class))).thenReturn(createdUser);

	createAccountUseCase.execute(payload);

	verify(keycloakUserProvisioningService).getOrCreateUser(eq(payload.user()), anyList());
		verify(patientModuleApi).createPatient(
		userId,
		"Luis",
		"Perez",
		"patient01",
		"luis@test.com",
		patientRequest
	);
	verify(doctorProvisioningApi, never()).createDoctor(any(), any(), any(), any(), any());
    }

    @Test
    void executeShouldAllowDoctorAndPatientInSameRequest() {
	UUID userId = UUID.fromString("33333333-3333-3333-3333-333333333333");
	UserSummary createdUser = new UserSummary(userId, "both01", "Maria", "Gomez", "maria@test.com");
	CreateDoctorRequest doctorRequest = new CreateDoctorRequest(
		DocumentType.CEDULA,
		"3001234567",
		List.of(SpecialtyCode.MEDICINA_GENERAL),
		LocalDate.of(2024, 1, 1),
		null,
		30,
		null
	);
	CreatePatientUserRequest patientRequest = new CreatePatientUserRequest(
		PatientDocumentType.PASAPORTE,
		"3007654321",
		PatientGender.FEMENINO,
		LocalDate.of(1999, 3, 10),
		"3001112233"
	);
	CreateSystemUserPayload payload = new CreateSystemUserPayload(
		buildUserRequest(),
		doctorRequest,
		patientRequest,
		List.of(Role.DOCTOR, Role.PATIENT, Role.DOCTOR)
	);

	when(keycloakUserProvisioningService.getOrCreateUser(any(co.edu.unicauca.piedrazul.backend.user.api.dto.input.CreateSystemUserRequest.class), any(java.util.List.class))).thenReturn(createdUser);

	assertDoesNotThrow(() -> createAccountUseCase.execute(payload));

	verify(keycloakUserProvisioningService).getOrCreateUser(eq(payload.user()), anyList());
	verify(doctorProvisioningApi).createDoctor(
		userId,
		"Maria",
		"Gomez",
		"both01",
		doctorRequest
	);
		verify(patientModuleApi).createPatient(
		userId,
		"Maria",
		"Gomez",
		"both01",
		"maria@test.com",
		patientRequest
	);
    }

    private CreateSystemUserRequest buildUserRequest() {
	return new CreateSystemUserRequest(
		"1001",
		"Ana",
		"Lopez",
		"ana@test.com",
		"secret123"
	);
    }

 */

}