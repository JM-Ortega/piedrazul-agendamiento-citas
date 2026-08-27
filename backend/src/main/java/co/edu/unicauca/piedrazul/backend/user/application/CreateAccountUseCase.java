package co.edu.unicauca.piedrazul.backend.user.application;

import co.edu.unicauca.piedrazul.backend.doctors.DoctorProvisioningApi;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.internal.CreateDoctorRequest;
import co.edu.unicauca.piedrazul.backend.patients.PatientModuleApi;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.internal.CreatePatientUserRequest;
import co.edu.unicauca.piedrazul.backend.shared.audit.SecurityContextExtractor;
import co.edu.unicauca.piedrazul.backend.shared.enums.Role;
import co.edu.unicauca.piedrazul.backend.user.PersonExternalService;
import co.edu.unicauca.piedrazul.backend.user.UserProvisioningApi;
import co.edu.unicauca.piedrazul.backend.user.api.dto.input.CreateSystemUserPayload;
import co.edu.unicauca.piedrazul.backend.user.api.dto.internal.PersonSummary;
import co.edu.unicauca.piedrazul.backend.user.api.dto.internal.UserSummary;
import co.edu.unicauca.piedrazul.backend.user.events.UserCreatedEvent;
import co.edu.unicauca.piedrazul.backend.user.exception.InvalidRoleAssignmentException;
import co.edu.unicauca.piedrazul.backend.user.exception.InvalidUserDataException;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class CreateAccountUseCase implements UserProvisioningApi {

    private final DoctorProvisioningApi doctorProvisioningApi;
    private final PatientModuleApi patientModuleApi;
    private final PersonExternalService personExternalService;
    private final KeycloakUserProvisioningService keycloakUserProvisioningService;


    public CreateAccountUseCase(
            DoctorProvisioningApi doctorProvisioningApi,
            PatientModuleApi patientModuleApi,
            PersonExternalService personExternalService,
            KeycloakUserProvisioningService keycloakUserProvisioningService
    ) {
        this.doctorProvisioningApi = doctorProvisioningApi;
        this.patientModuleApi = patientModuleApi;
        this.personExternalService = personExternalService;
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

        UserSummary existingUser = keycloakUserProvisioningService
            .findUserByUsername(payload.user().identification())
            .orElse(null);

        var user = keycloakUserProvisioningService.getOrCreateUser(payload.user(), roles);
        PersonSummary person = null;

        try {
            person = personExternalService.createPerson(
                    payload.user().identificationType(),
                    payload.user().identification(),
                    user.firstName(),
                    user.lastName(),
                    payload.user().phone(),
                    user.email(),
                    user.id()
            );

            if (roles.contains(Role.DOCTOR)) {
                createDoctor(person.id(), payload.doctor());
            }

            if (roles.contains(Role.PATIENT)) {
                createPatient(person.id(), payload.patient());
            }
        } catch (Exception ex) {
            rollbackCreation(
                    user.id(),
                    person != null ? person.id() : null,
                    roles,
                    existingUser
            );
            throw ex;
        }
    }

    private void createDoctor(UUID personId, CreateDoctorRequest doctorRequest) {
        if (doctorRequest == null) {
            throw new InvalidUserDataException("Los datos del médico son requeridos para crearlo");
        }

        doctorProvisioningApi.createDoctor(personId, doctorRequest);
    }

    private void createPatient(UUID personId, CreatePatientUserRequest patientRequest) {
        if (patientRequest == null) {
            throw new InvalidUserDataException("Los datos del paciente son requeridos para crearlo");
        }

        patientModuleApi.createPatientForExistingPerson(
                personId,
                patientRequest.sex(),
                patientRequest.birthDate(),
                patientRequest.guardianPhone()
        );
    }

    private void rollbackCreation(UUID userId, UUID personId, List<Role> roles, UserSummary existingUser) {
        try {
            if (personId != null) {
                if (roles.contains(Role.PATIENT)) {
                    patientModuleApi.deletePatient(personId);
                }

                if (roles.contains(Role.DOCTOR)) {
                    doctorProvisioningApi.deleteDoctor(personId);
                }

                personExternalService.deletePerson(personId);
            }
        } catch (Exception ignored) {
            // best effort rollback
        }

        try {
            if (existingUser == null) {
                keycloakUserProvisioningService.deleteUser(userId);
            } else {
                Set<Role> originalRoles = existingUser.roles().stream()
                        .map(Role::valueOf)
                        .collect(Collectors.toSet());

                roles.stream()
                        .filter(role -> !originalRoles.contains(role))
                        .forEach(role -> keycloakUserProvisioningService.revokeRole(userId, role));
            }
        } catch (Exception ignored) {
            // best effort rollback
        }
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
            throw new InvalidRoleAssignmentException("Combinación de roles no es valida: " + roles);
        }
    }
}
