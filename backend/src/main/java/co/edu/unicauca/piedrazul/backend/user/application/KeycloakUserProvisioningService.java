package co.edu.unicauca.piedrazul.backend.user.application;

import co.edu.unicauca.piedrazul.backend.shared.auth.Role;
import co.edu.unicauca.piedrazul.backend.user.UserProvisioningApi;
import co.edu.unicauca.piedrazul.backend.user.api.dto.input.CreateSystemUserPayload;
import co.edu.unicauca.piedrazul.backend.user.api.dto.input.CreateSystemUserRequest;
import co.edu.unicauca.piedrazul.backend.user.api.dto.internal.UserSummary;
import co.edu.unicauca.piedrazul.backend.user.exception.InvalidUserDataException;
import co.edu.unicauca.piedrazul.backend.user.infrastructure.KeycloakUserClient;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class KeycloakUserProvisioningService implements UserProvisioningApi {

    private final KeycloakUserClient keycloakClient;

    public KeycloakUserProvisioningService(KeycloakUserClient keycloakClient) {
        this.keycloakClient = keycloakClient;
    }

    @Override
    public UserSummary createUser(CreateSystemUserPayload payload) {
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

        return getOrCreateUser(payload.user(), roles);
    }

    private UserSummary getOrCreateUser(CreateSystemUserRequest request, List<Role> roles) {
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

    private UserSummary toUserSummary(UserRepresentation user) {
        return new UserSummary(
                UUID.fromString(user.getId()),
                user.getUsername(),
                user.getFirstName(),
                user.getLastName(),
                user.getEmail()
        );
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