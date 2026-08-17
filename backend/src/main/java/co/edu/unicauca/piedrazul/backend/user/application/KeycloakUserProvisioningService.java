package co.edu.unicauca.piedrazul.backend.user.application;

import co.edu.unicauca.piedrazul.backend.shared.enums.Role;
import co.edu.unicauca.piedrazul.backend.user.UserAccountProvisioningApi;
import co.edu.unicauca.piedrazul.backend.user.api.dto.input.CreateSystemUserRequest;
import co.edu.unicauca.piedrazul.backend.user.api.dto.internal.UserSummary;
import co.edu.unicauca.piedrazul.backend.user.exception.UserAlreadyExistsException;
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
                .map(existingUser -> assignRoles(toUserSummary(existingUser), roles))
                .orElseGet(() -> {
                    try {
                        return assignRoles(createUser(request), roles);
                    } catch (UserAlreadyExistsException conflict) {
                        // Un conflicto puede deberse a una creación concurrente; este flujo
                        // admite reutilización, por lo que recupera la cuenta existente.
                        return assignRoles(findExistingOrRethrow(request, conflict), roles);
                    }
                });
    }

    @Override
    public UserSummary createAccount(CreateSystemUserRequest request, List<Role> roles) {
        return assignRoles(createUser(request), roles);
    }

    @Override
    public UserSummary ensureAccount(CreateSystemUserRequest request, List<Role> roles) {
        UserSummary account = keycloakClient.findUserByUsername(request.identification())
                .map(this::toUserSummary)
                .orElseGet(() -> {
                    try {
                        return createUser(request);
                    } catch (UserAlreadyExistsException conflict) {
                        return findExistingOrRethrow(request, conflict);
                    }
                });

        // La identidad ya fue verificada; la contraseña solicitada pasa a controlar
        // la cuenta aunque esta ya existiera.
        keycloakClient.resetPassword(account.id(), request.password());

        return assignRoles(account, roles);
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

    private UserSummary createUser(CreateSystemUserRequest request) {
        return toUserSummary(keycloakClient.createUser(
                request.identification(),
                request.firstName(),
                request.lastName(),
                request.email(),
                request.password()
        ));
    }

    private UserSummary findExistingOrRethrow(CreateSystemUserRequest request, UserAlreadyExistsException conflict) {
        return keycloakClient.findUserByUsername(request.identification())
                .map(this::toUserSummary)
                .orElseThrow(() -> conflict);
    }

    private UserSummary assignRoles(UserSummary user, List<Role> roles) {
        roles.forEach(role -> keycloakClient.assignRoleIfMissing(user.id(), role));
        return user;
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
