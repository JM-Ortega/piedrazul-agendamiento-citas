package co.edu.unicauca.piedrazul.backend.user.api;

import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.internal.CreateDoctorRequest;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.input.CreateScheduleRequest;
import co.edu.unicauca.piedrazul.backend.doctors.domain.DocumentType;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Specialty;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Workday;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.internal.CreatePatientRequest;
import co.edu.unicauca.piedrazul.backend.shared.auth.Role;
import co.edu.unicauca.piedrazul.backend.user.api.dto.input.CreateSystemUserPayload;
import co.edu.unicauca.piedrazul.backend.user.api.dto.input.CreateSystemUserRequest;
import co.edu.unicauca.piedrazul.backend.user.application.CreateAccountUseCase;
import co.edu.unicauca.piedrazul.backend.user.application.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class UserControllerTest {

    @Mock
    private CreateAccountUseCase createAccountUseCase;

    @Mock
    private UserService userService;

    private UserController userController;

    @BeforeEach
    void setUp() {
    userController = new UserController(createAccountUseCase, userService);
    }

    @Test
    void createUserShouldDelegateToUseCaseForDoctorRole() {
    CreateSystemUserPayload payload = new CreateSystemUserPayload(
        new CreateSystemUserRequest(
            "doctor1",
            "Laura",
            "Perez",
            "laura@test.com",
            "Pass123!",
            List.of(Role.DOCTOR)
        ),
        new CreateDoctorRequest(
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
        ),
        null,
        List.of(Role.DOCTOR)
    );

    ResponseEntity<Void> response = userController.createUser(payload);

    assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    verify(createAccountUseCase).execute(payload);
    verifyNoInteractions(userService);
    }

    @Test
    void createUserShouldDelegateToUseCaseForPatientRole() {
        CreatePatientRequest patientRequest = mock(CreatePatientRequest.class);
    CreateSystemUserPayload payload = new CreateSystemUserPayload(
        new CreateSystemUserRequest(
            "20202020202",
            "Juan",
            "Ortega",
            "juan@test.com",
            "Patient123!",
            List.of(Role.PATIENT)
        ),
        null,
            patientRequest,
        List.of(Role.PATIENT)
    );

    ResponseEntity<Void> response = userController.createUser(payload);

    assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    verify(createAccountUseCase).execute(payload);
    verifyNoInteractions(userService);
    }

    @Test
    void getSystemUsersShouldDelegateToUserService() {
    ResponseEntity<List<co.edu.unicauca.piedrazul.backend.user.api.dto.output.SystemUserResponse>> response = userController.getSystemUsers();

    assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    verify(userService).getSystemUsers();
    }
}
