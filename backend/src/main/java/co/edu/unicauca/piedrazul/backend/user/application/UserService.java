package co.edu.unicauca.piedrazul.backend.user.application;

import co.edu.unicauca.piedrazul.backend.doctors.DoctorExternalService;
import co.edu.unicauca.piedrazul.backend.shared.enums.SpecialtyCode;
import co.edu.unicauca.piedrazul.backend.shared.enums.Role;
import co.edu.unicauca.piedrazul.backend.user.api.dto.internal.UserSummary;
import co.edu.unicauca.piedrazul.backend.user.api.dto.output.SystemDoctorResponse;
import co.edu.unicauca.piedrazul.backend.user.api.dto.output.SystemUserResponse;
import co.edu.unicauca.piedrazul.backend.user.exception.DoctorRoleRequiredException;
import co.edu.unicauca.piedrazul.backend.user.exception.UserNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Sort;
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

    public Page<SystemUserResponse> getSystemUsers(Pageable pageable) {
        List<UserSummary> doctors = keycloakUserService.findDoctors();
        List<UserSummary> schedulers = keycloakUserService.findSchedulers();

        Map<UUID, UserSummary> usersById = new LinkedHashMap<>();
        Stream.concat(doctors.stream(), schedulers.stream())
                .forEach(user -> usersById.putIfAbsent(user.id(), user));

        List<UserSummary> allUsers = new ArrayList<>(usersById.values());

        // 1. Aplicar Ordenamiento en Memoria
        applySorting(allUsers, pageable.getSort());

        int total = allUsers.size();
        // Calcular límites de la página
        int start = (int) pageable.getOffset();
        int end = Math.min((start + pageable.getPageSize()), total);

        if (start >= total) {
            return new PageImpl<>(List.of(), pageable, total);
        }

        // Paginar primero la lista base
        List<UserSummary> pagedUsers = allUsers.subList(start, end);

        // Consultar roles SOLO para los usuarios de la página actual
        Set<UUID> pagedUserIds = pagedUsers
                                    .stream()
                                    .map(UserSummary::id)
                                    .collect(Collectors.toSet());

        Map<UUID, List<String>> rolesByUserId = keycloakUserService.getUserRolesByIds(pagedUserIds);

        List<SystemUserResponse> content = pagedUsers.stream()
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

        return new PageImpl<>(content, pageable, total);
    }

    private static final Set<String> EXCLUDED_ROLES = Set.of(
            Role.PATIENT.name(),
            "default-roles-piedrazul"
    );

    public Page<SystemDoctorResponse> getSystemDoctors(Pageable pageable) {
        List<UserSummary> doctors = keycloakUserService.findDoctors();
        int total = doctors.size();

        if (doctors.isEmpty()) {
            return new PageImpl<>(List.of(), pageable, 0);
        }

        // Aplicar Ordenamiento en Memoria
        applySorting(doctors, pageable.getSort());

        int start = (int) pageable.getOffset();
        int end = Math.min((start + pageable.getPageSize()), total);

        if (start >= total) {
            return new PageImpl<>(List.of(), pageable, total);
        }

        // Paginar la lista ya ordenada en memoria
        List<UserSummary> pagedDoctors = doctors.subList(start, end);

        // Obtener IDs solo del subconjunto paginado
        Set<UUID> userIds = pagedDoctors.stream()
                .map(UserSummary::id)
                .collect(Collectors.toSet());

        // Consultar dependencias solo para la página visible
        Map<UUID, List<String>> rolesByUserId =
                keycloakUserService.getUserRolesByIds(userIds);

        Map<UUID, UUID> personIdsMap =
                personExternalServiceImp.findPersonIdsByUserIds(userIds);

        Map<UUID, List<SpecialtyCode>> specialties =
                doctorExternalService.findSpecialtiesByPersonIds(personIdsMap.values());

        List<SystemDoctorResponse> content = new ArrayList<>();

        for (UserSummary doctor : pagedDoctors) {
            UUID personId = personIdsMap.get(doctor.id());

            List<String> doctorSpecialties = specialties
                    .getOrDefault(personId, List.of())
                    .stream()
                    .map(Enum::name)
                    .toList();

            content.add(new SystemDoctorResponse(
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

        return new PageImpl<>(content, pageable, total);
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

    // Función auxiliar
    private void applySorting(List<UserSummary> users, Sort sort) {
        if (sort.isUnsorted()) {
            return;
        }

        Comparator<UserSummary> comparator = null;

        for (Sort.Order order : sort) {
            Comparator<UserSummary> currentComparator = switch (order.getProperty().toLowerCase()) {
                case "lastname" -> Comparator.comparing(UserSummary::lastName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
                case "firstname" -> Comparator.comparing(UserSummary::firstName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
                default -> Comparator.comparing(UserSummary::firstName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            };

            if (order.isDescending()) {
                currentComparator = currentComparator.reversed();
            }

            comparator = (comparator == null) ? currentComparator : comparator.thenComparing(currentComparator);
        }

        if (comparator != null) {
            users.sort(comparator);
        }
    }
}
