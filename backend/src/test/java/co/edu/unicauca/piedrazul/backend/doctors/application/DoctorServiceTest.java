package co.edu.unicauca.piedrazul.backend.doctors.application;

import co.edu.unicauca.piedrazul.backend.appointment.AppointmentExternalService;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.internal.CreateDoctorRequest;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.input.CreateScheduleRequest;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.output.DoctorResponse;
import co.edu.unicauca.piedrazul.backend.doctors.domain.DocumentType;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Doctor;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Specialty;
import co.edu.unicauca.piedrazul.backend.doctors.infrastructure.persistence.DoctorRepository;
import co.edu.unicauca.piedrazul.backend.shared.auth.Role;
import co.edu.unicauca.piedrazul.backend.user.UserModuleApi;
import co.edu.unicauca.piedrazul.backend.user.api.dto.input.CreateSystemUserRequest;
import co.edu.unicauca.piedrazul.backend.user.api.dto.output.DoctorUserProvisioningResult;
import co.edu.unicauca.piedrazul.backend.user.application.DoctorUserProvisioningPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DoctorServiceTest {

    @Mock
    private DoctorRepository doctorRepository;

    @Mock
    private DoctorUserProvisioningPort doctorUserProvisioningPort;

    @Mock
    private UserModuleApi userModuleApi;

    @Mock
    private AppointmentExternalService appointmentExternalService;

    private DoctorService doctorService;

    @BeforeEach
    void setUp() {
        doctorService = new DoctorService(doctorRepository, doctorUserProvisioningPort, userModuleApi, appointmentExternalService);
    }

    @Test
    void createDoctorShouldForwardProvidedRolesToUserProvisioning() {
        UUID userId = UUID.randomUUID();
        CreateDoctorRequest request = createDoctorRequest();

        when(doctorUserProvisioningPort.provisionDoctorUser(any(CreateSystemUserRequest.class)))
                .thenReturn(new DoctorUserProvisioningResult(userId, true, false));
        when(doctorRepository.save(any(Doctor.class))).thenAnswer(invocation -> invocation.getArgument(0));

        DoctorResponse response = doctorService.createDoctor(request, List.of(Role.DOCTOR, Role.SCHEDULER));

        ArgumentCaptor<CreateSystemUserRequest> requestCaptor = ArgumentCaptor.forClass(CreateSystemUserRequest.class);
        verify(doctorUserProvisioningPort).provisionDoctorUser(requestCaptor.capture());
        assertThat(requestCaptor.getValue().roles()).containsExactly(Role.DOCTOR, Role.SCHEDULER);
        assertThat(response.name()).isEqualTo("Laura Perez");
        assertThat(response.specialty()).isEqualTo("[QUIROPRAXIA]");
    }

    @Test
    void createDoctorShouldDefaultToDoctorRoleWhenNoRolesAreProvided() {
        UUID userId = UUID.randomUUID();
        CreateDoctorRequest request = createDoctorRequest();

        when(doctorUserProvisioningPort.provisionDoctorUser(any(CreateSystemUserRequest.class)))
                .thenReturn(new DoctorUserProvisioningResult(userId, true, false));
        when(doctorRepository.save(any(Doctor.class))).thenAnswer(invocation -> invocation.getArgument(0));

        doctorService.createDoctor(request);

        ArgumentCaptor<CreateSystemUserRequest> requestCaptor = ArgumentCaptor.forClass(CreateSystemUserRequest.class);
        verify(doctorUserProvisioningPort).provisionDoctorUser(requestCaptor.capture());
        assertThat(requestCaptor.getValue().roles()).containsExactly(Role.DOCTOR);
    }

    @Test
    void createDoctorForUserShouldUseProvidedUserIdWithoutProvisioning() {
        UUID userId = UUID.randomUUID();
        CreateDoctorRequest request = createDoctorRequest();

        when(doctorRepository.save(any(Doctor.class))).thenAnswer(invocation -> invocation.getArgument(0));

        DoctorResponse response = doctorService.createDoctorForUser(userId, request);

        assertThat(response.name()).isEqualTo("Laura Perez");
        verify(doctorUserProvisioningPort, never()).provisionDoctorUser(any(CreateSystemUserRequest.class));
    }

    private CreateDoctorRequest createDoctorRequest() {
        return new CreateDoctorRequest(
                "Laura",
                "Perez",
                DocumentType.CEDULA,
                "1234567890",
                "3001234567",
                List.of(Specialty.QUIROPRAXIA),
                LocalDate.now(),
                LocalDate.now().plusMonths(1),
                30,
                List.of(new CreateScheduleRequest(LocalTime.of(8, 0), LocalTime.of(12, 0), co.edu.unicauca.piedrazul.backend.doctors.domain.Workday.LUNES)),
                "laura@test.com",
                "Pass123!"
        );
    }
}