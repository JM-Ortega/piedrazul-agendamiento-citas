package co.edu.unicauca.piedrazul.backend.user.application;

import co.edu.unicauca.piedrazul.backend.shared.enums.Role;
import co.edu.unicauca.piedrazul.backend.user.UserAccountProvisioningApi;
import co.edu.unicauca.piedrazul.backend.user.api.dto.input.CreateSystemUserRequest;
import co.edu.unicauca.piedrazul.backend.user.api.dto.internal.UserSummary;
import co.edu.unicauca.piedrazul.backend.user.infrastructure.KeycloakUserClient;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class KeycloakUserProvisioningService implements UserAccountProvisioningApi {

    private final KeycloakUserClient keycloakClient;

    public KeycloakUserProvisioningService(KeycloakUserClient keycloakClient) {
        this.keycloakClient = keycloakClient;
    }

    @Override
    public UserSummary getOrCreateUser(CreateSystemUserRequest request, List<Role> roles) {
        return keycloakClient.findUserByUsername(request.identification())
                .map(existingUser -> {
                    UUID userId = UUID.fromString(existingUser.getId());
                    roles.forEach(role -> keycloakClient.assignRoleIfMissing(userId, role));
                    return toUserSummary(existingUser);
                })
                .orElseGet(() -> {
                    UserSummary createdUser = toUserSummary(
                            keycloakClient.createUser(
                                    request.identification(),
                                    request.firstName(),
                                    request.lastName(),
                                    request.email(),
                                    request.password()
                            )
                    );
                    roles.forEach(role -> keycloakClient.assignRoleIfMissing(createdUser.id(), role));
                    return createdUser;
                });
    }

    public Optional<UserSummary> findUserByUsername(String username) {
        return keycloakClient.findUserByUsername(username).map(this::toUserSummary);
    }

    public void deleteUser(UUID userId) {
        keycloakClient.deleteUser(userId);
    }

    public void revokeRole(UUID userId, Role role) {
        keycloakClient.revokeRoleIfPresent(userId, role);
    }

    private UserSummary toUserSummary(UserRepresentation user) {
        return new UserSummary(
                UUID.fromString(user.getId()),
                user.getUsername(),
                user.getFirstName(),
                user.getLastName(),
                user.getEmail(),
                user.getRealmRoles()
        );
    }
}