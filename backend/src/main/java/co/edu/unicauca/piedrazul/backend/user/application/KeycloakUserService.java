package co.edu.unicauca.piedrazul.backend.user.application;

import co.edu.unicauca.piedrazul.backend.shared.auth.Role;
import co.edu.unicauca.piedrazul.backend.user.UserModuleApi;
import co.edu.unicauca.piedrazul.backend.user.api.dto.internal.UserSummary;
import co.edu.unicauca.piedrazul.backend.user.infrastructure.KeycloakUserClient;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class KeycloakUserService implements UserModuleApi {

    private final KeycloakUserClient keycloakClient;

    public KeycloakUserService(KeycloakUserClient keycloakClient) {
        this.keycloakClient = keycloakClient;
    }

    @Override
    public UUID getOrCreatePatientUser(
            String username,
            String firstName,
            String lastName,
            String email,
            String password
    ) {
        return getOrCreateUserWithRole(
                username,
                firstName,
                lastName,
                email,
                password,
                Role.PATIENT
        );
    }

    @Override
    public UUID getOrCreateDoctorUser(
            String username,
            String firstName,
            String lastName,
            String email,
            String password
    ) {
        return getOrCreateUserWithRole(
                username,
                firstName,
                lastName,
                email,
                password,
                Role.DOCTOR
        );
    }

    @Override
    public UUID getOrCreateSchedulerUser(
            String username,
            String firstName,
            String lastName,
            String email,
            String password
    ) {
        return getOrCreateUserWithRole(
                username,
                firstName,
                lastName,
                email,
                password,
                Role.SCHEDULER
        );
    }

    @Override
    public UUID getOrCreateAdminUser(
            String username,
            String firstName,
            String lastName,
            String email,
            String password
    ) {
        return getOrCreateUserWithRole(
                username,
                firstName,
                lastName,
                email,
                password,
                Role.ADMIN
        );
    }

    @Override
    public boolean existsById(UUID id) {
        return keycloakClient.existsUser(id);
    }

    @Override
    public void activateUser(UUID id) {
        keycloakClient.activateUser(id);
    }

    @Override
    public void deactivateUser(UUID id) {
        keycloakClient.deactivateUser(id);
    }

    private UUID getOrCreateUserWithRole(
            String username,
            String firstName,
            String lastName,
            String email,
            String password,
            Role role
    ) {
        return keycloakClient.findUserIdByUsername(username)
                .map(userId -> {
                    keycloakClient.assignRoleIfMissing(userId, role);
                    return userId;
                })
                .orElseGet(() ->
                        keycloakClient.createUser(
                                username,
                                firstName,
                                lastName,
                                email,
                                password,
                                role
                        )
                );
    }

    @Override
    public Optional<UUID> findUserIdByUsername(String username) {
        return keycloakClient.findUserIdByUsername(username);
    }

    @Override
    public List<UserSummary> findSchedulers() {
        return keycloakClient.findUsersByRole(Role.SCHEDULER)
                .stream()
                .map(this::toUserSummary)
                .toList();
    }

    @Override
    public boolean hasSchedulerRole(UUID userId) {
        return keycloakClient.userHasRole(userId, Role.SCHEDULER);
    }

    @Override
    public void ensurePatientRole(UUID userId) {
        keycloakClient.assignRoleIfMissing(userId, Role.PATIENT);
    }

    @Override
    public void ensureSchedulerRole(UUID userId) {
        keycloakClient.assignRoleIfMissing(userId, Role.SCHEDULER);
    }

    private UserSummary toUserSummary(UserRepresentation user) {
        return new UserSummary(
                UUID.fromString(user.getId()),
                user.getUsername(),
                user.getFirstName(),
                user.getLastName(),
                user.getEmail()
        );
    }
}