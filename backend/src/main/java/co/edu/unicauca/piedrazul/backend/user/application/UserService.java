package co.edu.unicauca.piedrazul.backend.user.application;

import co.edu.unicauca.piedrazul.backend.doctors.DoctorExternalService;
import co.edu.unicauca.piedrazul.backend.shared.enums.SpecialtyCode;
import co.edu.unicauca.piedrazul.backend.shared.enums.Role;
import co.edu.unicauca.piedrazul.backend.user.api.dto.internal.UserSummary;
import co.edu.unicauca.piedrazul.backend.user.api.dto.output.SystemDoctorResponse;
import co.edu.unicauca.piedrazul.backend.user.api.dto.output.SystemUserResponse;
import co.edu.unicauca.piedrazul.backend.user.exception.DoctorRoleRequiredException;
import co.edu.unicauca.piedrazul.backend.user.exception.UserNotFoundException;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class UserService {
    private final KeycloakUserService keycloakUserService;
    private final DoctorExternalService doctorExternalService;
    private final PersonExternalServiceImp personExternalServiceImp;

    public UserService(KeycloakUserService keycloakUserService, DoctorExternalService doctorExternalService
    , PersonExternalServiceImp personExternalServiceImp) {
        this.keycloakUserService = keycloakUserService;
        this.doctorExternalService = doctorExternalService;
        this.personExternalServiceImp = personExternalServiceImp;
    }

    public List<SystemUserResponse> getSystemUsers() {
        List<UserSummary> doctors = keycloakUserService.findDoctors();
        List<UserSummary> schedulers = keycloakUserService.findSchedulers();

        Map<UUID, UserSummary> usersById = new LinkedHashMap<>();

        Stream.concat(doctors.stream(), schedulers.stream())
                .forEach(user -> usersById.putIfAbsent(user.id(), user));

        Map<UUID, List<String>> rolesByUserId =
                keycloakUserService.getUserRolesByIds(usersById.keySet());

        return usersById.values().stream()
                .map(user -> new SystemUserResponse(
                        user.id(),
                        user.firstName(),
                        user.lastName(),
                        user.username(),
                        rolesByUserId.getOrDefault(user.id(), List.of())
                                .stream()
                                .filter(role -> !EXCLUDED_ROLES.contains(role))
                                .distinct()
                                .toList()
                ))
                .toList();
    }

    private static final Set<String> EXCLUDED_ROLES = Set.of(
            Role.PATIENT.name(),
            "default-roles-piedrazul"
    );

    public List<SystemDoctorResponse> getSystemDoctors() {
        List<UserSummary> doctors = keycloakUserService.findDoctors();

        if (doctors.isEmpty()) {
            return List.of();
        }

        Set<UUID> userIds = doctors.stream()
                .map(UserSummary::id)
                .collect(Collectors.toSet());

        Map<UUID, List<String>> rolesByUserId =
                keycloakUserService.getUserRolesByIds(userIds);

        Map<UUID, UUID> personIdsMap =
                personExternalServiceImp.findPersonIdsByUserIds(userIds);

        Map<UUID, List<SpecialtyCode>> specialties =
                doctorExternalService.findSpecialtiesByPersonIds(personIdsMap.values());

        List<SystemDoctorResponse> result = new ArrayList<>();

        for (UserSummary doctor : doctors) {
            UUID personId = personIdsMap.get(doctor.id());

            List<String> doctorSpecialties = specialties
                    .getOrDefault(personId, List.of())
                    .stream()
                    .map(Enum::name)
                    .toList();

            result.add(new SystemDoctorResponse(
                    personId,
                    doctor.firstName(),
                    doctor.lastName(),
                    doctor.username(),
                    rolesByUserId.getOrDefault(doctor.id(), List.of())
                            .stream()
                            .filter(role -> !EXCLUDED_ROLES.contains(role))
                            .distinct()
                            .toList(),
                    doctorSpecialties
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

    private boolean hasRole(UUID userId, Role role) {
        return keycloakUserService.getUserRoles(userId).contains(role.name());
    }
}
