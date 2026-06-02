package co.edu.unicauca.piedrazul.backend.user.application;

import co.edu.unicauca.piedrazul.backend.doctors.DoctorProvisioningApi;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.internal.CreateDoctorRequest;
import co.edu.unicauca.piedrazul.backend.patients.PatientModuleApi;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.internal.CreatePatientUserRequest;
import co.edu.unicauca.piedrazul.backend.shared.auth.Role;
import co.edu.unicauca.piedrazul.backend.user.UserProvisioningApi;
import co.edu.unicauca.piedrazul.backend.user.api.dto.input.CreateSystemUserPayload;
import co.edu.unicauca.piedrazul.backend.user.exception.InvalidUserDataException;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class CreateAccountUseCase implements UserProvisioningApi {

    private final DoctorProvisioningApi doctorProvisioningApi;
    private final PatientModuleApi patientModuleApi;
    private final KeycloakUserProvisioningService keycloakUserProvisioningService;

    public CreateAccountUseCase(
            DoctorProvisioningApi doctorProvisioningApi,
            PatientModuleApi patientModuleApi,
            KeycloakUserProvisioningService keycloakUserProvisioningService
    ) {
        this.doctorProvisioningApi = doctorProvisioningApi;
        this.patientModuleApi = patientModuleApi;
        this.keycloakUserProvisioningService = keycloakUserProvisioningService;
    }

    @Override
    public void createUser(CreateSystemUserPayload request){
        execute(request);
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
        validateRoles(roles);

        var user = keycloakUserProvisioningService.getOrCreateUser(payload.user(), roles);

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

        patientModuleApi.createPatient(userId, firstName, lastName, identificacion, email, patientRequest);
    }

    private void validateRoles(List<Role> roles) {
        Set<Role> roleSet = EnumSet.copyOf(roles);

        Set<Set<Role>> validCombinations = Set.of(
                Set.of(Role.PATIENT),
                Set.of(Role.ADMIN),
                Set.of(Role.DOCTOR),
                Set.of(Role.SCHEDULER),
                Set.of(Role.DOCTOR, Role.SCHEDULER),
                Set.of(Role.DOCTOR, Role.PATIENT),
                Set.of(Role.DOCTOR, Role.SCHEDULER, Role.PATIENT)
        );

        if (!validCombinations.contains(roleSet)) {
            throw new InvalidUserDataException("Combinación de roles no valida: " + roles);
        }
    }
}