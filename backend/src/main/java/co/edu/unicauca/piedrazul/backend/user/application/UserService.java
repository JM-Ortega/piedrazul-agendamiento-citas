package co.edu.unicauca.piedrazul.backend.user.application;

import co.edu.unicauca.piedrazul.backend.doctors.DoctorExternalService;
import co.edu.unicauca.piedrazul.backend.shared.auth.Role;
import co.edu.unicauca.piedrazul.backend.user.api.dto.internal.UserSummary;
import co.edu.unicauca.piedrazul.backend.user.api.dto.output.SystemDoctorResponse;
import co.edu.unicauca.piedrazul.backend.user.api.dto.output.SystemUserResponse;
import co.edu.unicauca.piedrazul.backend.user.exception.DoctorRoleRequiredException;
import co.edu.unicauca.piedrazul.backend.user.exception.UserNotFoundException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

@Service
public class UserService {
    private final KeycloakUserService keycloakUserService;
    private final DoctorExternalService doctorExternalService;

    public UserService(KeycloakUserService keycloakUserService, DoctorExternalService doctorExternalService) {
        this.keycloakUserService = keycloakUserService;
        this.doctorExternalService = doctorExternalService;
    }

    public List<SystemUserResponse> getSystemUsers() {
        List<UserSummary> doctors = keycloakUserService.findDoctors();
        List<UserSummary> schedulers = keycloakUserService.findSchedulers();

        List<UserSummary> keycloakUsers = Stream.concat(
                doctors.stream(),
                schedulers.stream()
        ).distinct().toList();

        List<SystemUserResponse> result = new ArrayList<>();

        for (UserSummary user : keycloakUsers) {
            List<String> roles = keycloakUserService.getUserRoles(user.id())
                    .stream()
                    .filter(role -> role.equals(Role.DOCTOR.name()) || role.equals(Role.SCHEDULER.name()))
                    .toList();

            result.add(new SystemUserResponse(
                    user.id(),
                    user.firstName(),
                    user.lastName(),
                    user.username(),
                    roles
            ));
        }

        return result;
    }

    public List<SystemDoctorResponse> getSystemDoctors() {
        List<UserSummary> doctors = keycloakUserService.findDoctors();

        List<SystemDoctorResponse> result = new ArrayList<>();

        for (UserSummary doctor : doctors) {
            List<String> roles = keycloakUserService.getUserRoles(doctor.id())
                    .stream()
                    .filter(role -> role.equals(Role.DOCTOR.name()) || role.equals(Role.SCHEDULER.name()))
                    .toList();

            List<String> specialties = doctorExternalService.findSpecialtiesByIdentification(doctor.username())
                    .stream()
                    .map(Objects::toString)
                    .toList();

            result.add(new SystemDoctorResponse(
                    doctor.id(),
                    doctor.firstName(),
                    doctor.lastName(),
                    doctor.username(),
                    roles,
                    specialties
            ));
        }
        return result;
    }

    public void giveDoctorScheduleRole(String username){
        UserSummary user = keycloakUserService.findUserByUsername(username)
                .orElseThrow(() -> new UserNotFoundException("Usuario no encontrado"));

        if(hasRole(user.id(), Role.DOCTOR)){
            keycloakUserService.ensureSchedulerRole(user.id());
        }else{
            throw new DoctorRoleRequiredException("Solo se puede añadir el rol de Agendador a un usuario de tipo Doctor");
        }
    }

    public void revokeDoctorSchedulerRole(String username){
        UserSummary user = keycloakUserService.findUserByUsername(username)
                .orElseThrow(() -> new UserNotFoundException("Usuario no encontrado"));

        if(hasRole(user.id(), Role.DOCTOR)){
            keycloakUserService.revokeSchedulerRole(user.id());
        }else{
            throw new DoctorRoleRequiredException("Solo se puede revocar el rol de Agendador a un usuario de tipo Doctor");
        }
    }

    private List<Role> normalizeRoles(List<Role> requestedRoles) {
        return requestedRoles.stream().distinct().toList();
    }

    private boolean hasRole(UUID userId, Role role) {
        return keycloakUserService.getUserRoles(userId).contains(role.name());
    }
}
