package co.edu.unicauca.piedrazul.backend.user.application;

import co.edu.unicauca.piedrazul.backend.doctors.DoctorExternalService;
import co.edu.unicauca.piedrazul.backend.doctors.DoctorProvisioningApi;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.internal.CreateDoctorRequest;
import co.edu.unicauca.piedrazul.backend.patients.PatientModuleApi;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.internal.CreatePatientUserRequest;
import co.edu.unicauca.piedrazul.backend.shared.auth.Role;
import co.edu.unicauca.piedrazul.backend.user.UserProvisioningApi;
import co.edu.unicauca.piedrazul.backend.user.api.dto.input.CreateSystemUserPayload;
import co.edu.unicauca.piedrazul.backend.user.exception.InvalidUserDataException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class CreateAccountUseCase {

    private final UserProvisioningApi userProvisioningApi;
    private final DoctorProvisioningApi doctorProvisioningApi;
    private final PatientModuleApi patientModuleApi;

    public CreateAccountUseCase(
            UserProvisioningApi userProvisioningApi,
            DoctorProvisioningApi doctorProvisioningApi,
            PatientModuleApi patientModuleApi
    ) {
        this.userProvisioningApi = userProvisioningApi;
        this.doctorProvisioningApi = doctorProvisioningApi;
        this.patientModuleApi = patientModuleApi;
    }

    public void execute(CreateSystemUserPayload payload) {
        if (payload == null) {
            throw new InvalidUserDataException("El payload de creación de usuario es requerido");
        }

        if (payload.user() == null) {
            throw new InvalidUserDataException("Los datos del usuario son requeridos");
        }

        if (payload.roles() == null || payload.roles().isEmpty()) {
            throw new InvalidUserDataException("Al menos un rol es requerido");
        }

        List<Role> roles = payload.roles().stream().distinct().toList();
        var user = userProvisioningApi.createUser(payload);

        if (roles.contains(Role.DOCTOR)) {
            createDoctor(user.id(), user.firstName(), user.lastName(), user.username(), payload.doctor());
        }

        if (roles.contains(Role.PATIENT)) {
            createPatient(user.id(), user.firstName(), user.lastName(), user.username(), user.email(), payload.patient());
        }
    }

    private void createDoctor(UUID userId, String firstName, String lastName, String identificacion, CreateDoctorRequest doctorRequest) {
        if (doctorRequest == null) {
            throw new InvalidUserDataException("Los datos del médico son requeridos para crearlo");
        }

        doctorProvisioningApi.createDoctor(userId, firstName, lastName, identificacion, doctorRequest);
    }

    private void createPatient(UUID userId, String firstName, String lastName, String identificacion, String email, CreatePatientUserRequest patientRequest) {
        if (patientRequest == null) {
            throw new InvalidUserDataException("Los datos del paciente son requeridos para crearlo");
        }

        patientModuleApi.createPatientWithUser(userId, firstName, lastName, identificacion, email, patientRequest);
    }


}