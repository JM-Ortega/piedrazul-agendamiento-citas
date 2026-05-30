package co.edu.unicauca.piedrazul.backend.user.application;

import co.edu.unicauca.piedrazul.backend.shared.auth.Role;
import co.edu.unicauca.piedrazul.backend.user.UserModuleApi;
import co.edu.unicauca.piedrazul.backend.user.api.dto.internal.UserSummary;
import co.edu.unicauca.piedrazul.backend.user.infrastructure.KeycloakUserClient;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class KeycloakUserService implements UserModuleApi {

    private final KeycloakUserClient keycloakClient;

    public KeycloakUserService(KeycloakUserClient keycloakClient) {
        this.keycloakClient = keycloakClient;
    }

    @Override
    public Optional<UserSummary> findUserByUsername(String username) {
        return keycloakClient.findUserByUsername(username).map(this::toUserSummary);
    }

    @Override
    public List<UserSummary> findSchedulers() {
        return findUsersByRole(Role.SCHEDULER);
    }

    @Override
    public List<UserSummary> findDoctors() {
        return findUsersByRole(Role.DOCTOR);
    }

    @Override
    public List<String> getUserRoles (UUID userId) {
        return keycloakClient.getUserRoles(userId);
    }

    @Override
    public void ensureSchedulerRole(UUID userId) {
        keycloakClient.assignRoleIfMissing(userId, Role.SCHEDULER);
    }

    @Override
    public void revokeSchedulerRole(UUID userId) {
        keycloakClient.revokeRoleIfPresent(userId, Role.SCHEDULER);
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

    @Override
    public void deleteUser(UUID id) {
        keycloakClient.deleteUser(id);
    }

    private List<UserSummary> findUsersByRole(Role role) {
        return keycloakClient.findUsersByRole(role)
                .stream()
                .map(this::toUserSummary)
                .toList();
    }

    private UserSummary toUserSummary(org.keycloak.representations.idm.UserRepresentation user) {
        return new UserSummary(
                UUID.fromString(user.getId()),
                user.getUsername(),
                user.getFirstName(),
                user.getLastName(),
                user.getEmail()
        );
    }
}