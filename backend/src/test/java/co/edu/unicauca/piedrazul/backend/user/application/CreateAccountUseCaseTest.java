package co.edu.unicauca.piedrazul.backend.user.application;

import co.edu.unicauca.piedrazul.backend.doctors.DoctorExternalService;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.internal.CreateDoctorRequest;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.input.CreateScheduleRequest;
import co.edu.unicauca.piedrazul.backend.doctors.domain.DocumentType;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Specialty;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Workday;
import co.edu.unicauca.piedrazul.backend.patients.PatientModuleApi;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.internal.CreatePatientRequest;
import co.edu.unicauca.piedrazul.backend.shared.auth.Role;
import co.edu.unicauca.piedrazul.backend.user.api.dto.input.CreateSystemUserPayload;
import co.edu.unicauca.piedrazul.backend.user.api.dto.input.CreateSystemUserRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CreateAccountUseCaseTest {

    @Mock
    private UserService userService;

    @Mock
    private DoctorExternalService doctorExternalService;

    @Mock
    private PatientModuleApi patientModuleApi;

    @InjectMocks
    private CreateAccountUseCase createAccountUseCase;

    @Test
    void executeShouldCreateDoctorAccountAndDelegateDoctorCreation() {
        UUID userId = UUID.randomUUID();
        CreateDoctorRequest doctorRequest = new CreateDoctorRequest(
                "Laura",
                "Perez",
                DocumentType.CEDULA,
                "1234567890",
                "3001234567",
                List.of(Specialty.QUIROPRAXIA),
                LocalDate.now().plusDays(1),
                LocalDate.now().plusMonths(1),
                30,
                List.of(new CreateScheduleRequest(LocalTime.of(8, 0), LocalTime.of(12, 0), Workday.LUNES)),
                "laura@test.com",
                "Pass123!"
        );
        CreateSystemUserRequest userRequest = new CreateSystemUserRequest(
                "doctor1",
                "Laura",
                "Perez",
                "laura@test.com",
                "Pass123!",
                List.of(Role.DOCTOR, Role.SCHEDULER)
        );
        CreateSystemUserPayload payload = new CreateSystemUserPayload(userRequest, doctorRequest, null, List.of(Role.DOCTOR, Role.SCHEDULER));

        when(userService.createUser(any(CreateSystemUserRequest.class))).thenReturn(userId);

        UUID result = createAccountUseCase.execute(payload);

        assertThat(result).isEqualTo(userId);
        ArgumentCaptor<CreateSystemUserRequest> userRequestCaptor = ArgumentCaptor.forClass(CreateSystemUserRequest.class);
        verify(userService).createUser(userRequestCaptor.capture());
        assertThat(userRequestCaptor.getValue().roles()).containsExactly(Role.DOCTOR, Role.SCHEDULER);
        verify(doctorExternalService).createDoctor(userId, doctorRequest);
        verify(patientModuleApi, never()).createPatientWithUser(any(UUID.class), any());
    }

    @Test
    void executeShouldCreatePatientAccountAndDelegatePatientCreation() {
        UUID userId = UUID.randomUUID();
        CreatePatientRequest patientRequest = mock(CreatePatientRequest.class);
        CreateSystemUserRequest userRequest = new CreateSystemUserRequest(
                "20202020202",
                "Juan",
                "Ortega",
                "juan@test.com",
                "Patient123!",
                List.of(Role.PATIENT)
        );
        CreateSystemUserPayload payload = new CreateSystemUserPayload(userRequest, null, patientRequest, List.of(Role.PATIENT));

        when(userService.createUser(any(CreateSystemUserRequest.class))).thenReturn(userId);

        UUID result = createAccountUseCase.execute(payload);

        assertThat(result).isEqualTo(userId);
        verify(userService).createUser(userRequest);
        verify(patientModuleApi).createPatientWithUser(userId, patientRequest);
        verify(doctorExternalService, never()).createDoctor(any(UUID.class), any());
    }

    @Test
    void executeShouldOnlyCreateUserForSingleRoleWithoutDomainPayload() {
        UUID userId = UUID.randomUUID();
        CreateSystemUserRequest userRequest = new CreateSystemUserRequest(
                "admin1",
                "Ana",
                "Lopez",
                "ana@test.com",
                "secret123",
                List.of(Role.ADMIN)
        );
        CreateSystemUserPayload payload = new CreateSystemUserPayload(userRequest, null, null, List.of(Role.ADMIN));

        when(userService.createUser(any(CreateSystemUserRequest.class))).thenReturn(userId);

        UUID result = createAccountUseCase.execute(payload);

        assertThat(result).isEqualTo(userId);
        verify(userService).createUser(userRequest);
        verify(doctorExternalService, never()).createDoctor(any(UUID.class), any());
        verify(patientModuleApi, never()).createPatientWithUser(any(UUID.class), any());
    }
}