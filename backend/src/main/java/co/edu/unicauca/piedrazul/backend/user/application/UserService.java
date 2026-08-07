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

        Map<UUID, UserSummary> keycloakUsers = new LinkedHashMap<>();

        Stream.concat(doctors.stream(), schedulers.stream())
                .forEach(user -> keycloakUsers.putIfAbsent(user.id(), user));

        Map<UUID, List<String>> rolesByUserId = keycloakUserService.getUserRolesByIds(keycloakUsers.keySet());

        List<SystemUserResponse> result = new ArrayList<>();

        for (UserSummary user : keycloakUsers.values()) {
            List<String> roles = resolveRoles(user, rolesByUserId);

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

        List<UUID> userIds = doctors.stream()
                .map(UserSummary::id)
                .toList();

        if(userIds.isEmpty()){
            throw  new UserNotFoundException("No hay doctores en el sistema");
        }

        // 1. Obtener el mapa de userId -> personId
        Map<UUID, UUID> personIdsMap = personExternalServiceImp.findPersonIdsByUserIds(userIds);

        // 2. Extraer la lista real de personIds
        List<UUID> personIds = new ArrayList<>(personIdsMap.values());

        // 3. Consultar especialidades usando personIds, NO userIds
        Map<UUID, List<SpecialtyCode>> specialties = doctorExternalService.findSpecialtiesByPersonIds(personIds);

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
                    doctor.roles(),
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

    private List<String> resolveRoles(UserSummary user, Map<UUID, List<String>> rolesByUserId) {
        List<String> roles = Optional.ofNullable(rolesByUserId.get(user.id()))
                .orElseGet(() -> keycloakUserService.getUserRoles(user.id()));

        if (roles == null || roles.isEmpty()) {
            roles = user.roles();
        }

        return roles.stream()
                .filter(role -> !Role.PATIENT.name().equals(role))
                .distinct()
                .toList();
    }

    private boolean hasRole(UUID userId, Role role) {
        return keycloakUserService.getUserRoles(userId).contains(role.name());
    }
}
