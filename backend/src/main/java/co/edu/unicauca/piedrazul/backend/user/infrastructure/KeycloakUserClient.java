package co.edu.unicauca.piedrazul.backend.user.infrastructure;

import co.edu.unicauca.piedrazul.backend.config.security.KeycloakProperties;
import co.edu.unicauca.piedrazul.backend.shared.auth.Role;
import co.edu.unicauca.piedrazul.backend.user.exception.IdentityProviderException;
import co.edu.unicauca.piedrazul.backend.user.exception.InvalidUserDataException;
import co.edu.unicauca.piedrazul.backend.user.exception.UserAlreadyExistsException;
import jakarta.ws.rs.core.Response;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class KeycloakUserClient {

    private static final Logger log = LoggerFactory.getLogger(KeycloakUserClient.class);

    private final Keycloak keycloak;
    private final KeycloakProperties props;

    public KeycloakUserClient(Keycloak keycloak, KeycloakProperties props) {
        this.keycloak = keycloak;
        this.props = props;
    }

    public UserRepresentation createUser(
            String username,
            String firstName,
            String lastName,
            String email,
            String password
    ) {
        RealmResource realm = keycloak.realm(props.getRealm());

        CredentialRepresentation credential = new CredentialRepresentation();
        credential.setType(CredentialRepresentation.PASSWORD);
        credential.setValue(password);
        credential.setTemporary(false);

        UserRepresentation user = new UserRepresentation();
        user.setUsername(username);
        user.setFirstName(firstName != null ? firstName : "");
        user.setLastName(lastName != null ? lastName : "");
        user.setEmail(email != null ? email : "");
        user.setEnabled(true);
        user.setEmailVerified(true);
        user.setCredentials(List.of(credential));

        String keycloakId;

        try (Response response = realm.users().create(user)) {
            int status = response.getStatus();

            if (status == Response.Status.CONFLICT.getStatusCode()) {
                return findUserByUsername(username)
                        .orElseThrow(() -> new IdentityProviderException(
                                "Conflicto al crear usuario pero no se pudo recuperar: " + username
                        ));
            }

            if (status == Response.Status.BAD_REQUEST.getStatusCode()) {
                String errorBody = response.hasEntity() ? response.readEntity(String.class) : "";
                log.warn("Datos inválidos en Keycloak: {}", errorBody);
                throw new InvalidUserDataException("Datos inválidos para crear el usuario");
            }

            if (status != Response.Status.CREATED.getStatusCode()) {
                String errorBody = response.hasEntity() ? response.readEntity(String.class) : "";
                log.error("Error Keycloak: {}", errorBody);
                throw new IdentityProviderException("No se pudo crear el usuario");
            }

            String location = response.getHeaderString("Location");
            if (location == null || location.isBlank()) {
                throw new IdentityProviderException("No se pudo obtener el ID del usuario");
            }

            keycloakId = location.substring(location.lastIndexOf('/') + 1);
        }

        user.setId(keycloakId);
        return user;
    }

    public List<UserRepresentation> findUsersByRole(Role role) {
        return keycloak.realm(props.getRealm())
                .roles()
                .get(role.name())
                .getUserMembers();
    }

    public Optional<UserRepresentation> findUserByUsername(String username) {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }

        return keycloak.realm(props.getRealm())
                .users()
                .searchByUsername(username, true)
                .stream()
                .findFirst();
    }

    public void assignRoleIfMissing(UUID keycloakId, Role role) {
        if (!userHasRole(keycloakId, role)) {
            assignRealmRole(keycloakId.toString(), role);
        }
    }

    public void revokeRoleIfPresent(UUID keycloakId, Role role) {

        if (userHasRole(keycloakId, role)) {
            revokeRealmRole(keycloakId.toString(), role);
        }
    }

    public void activateUser(UUID keycloakId) {
        UserRepresentation user = new UserRepresentation();
        user.setEnabled(true);

        keycloak.realm(props.getRealm())
                .users()
                .get(keycloakId.toString())
                .update(user);
    }

    public void deactivateUser(UUID keycloakId) {
        UserRepresentation user = new UserRepresentation();
        user.setEnabled(false);

        keycloak.realm(props.getRealm())
                .users()
                .get(keycloakId.toString())
                .update(user);
    }

    public void deleteUser(UUID keycloakId) {
        keycloak.realm(props.getRealm())
                .users()
                .get(keycloakId.toString())
                .remove();
    }

    public boolean existsUser(UUID keycloakId) {
        try {
            keycloak.realm(props.getRealm())
                    .users()
                    .get(keycloakId.toString())
                    .toRepresentation();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public List<String> getUserRoles(UUID keycloakId) {
        return keycloak.realm(props.getRealm())
                .users()
                .get(keycloakId.toString())
                .roles()
                .realmLevel()
                .listAll()
                .stream()
                .map(RoleRepresentation::getName)
                .toList();
    }

    public boolean userHasRole(UUID keycloakId, Role role) {
        List<RoleRepresentation> assignedRoles = keycloak.realm(props.getRealm())
                .users()
                .get(keycloakId.toString())
                .roles()
                .realmLevel()
                .listAll();

        return assignedRoles.stream()
                .anyMatch(assignedRole -> assignedRole.getName().equals(role.name()));
    }

    private void assignRealmRole(String keycloakId, Role role) {
        RealmResource realm = keycloak.realm(props.getRealm());

        RoleRepresentation realmRole = realm.roles()
                .get(role.name())
                .toRepresentation();

        realm.users()
                .get(keycloakId)
                .roles()
                .realmLevel()
                .add(List.of(realmRole));
    }

    private void revokeRealmRole(String keycloakId, Role role) {

        RealmResource realm = keycloak.realm(props.getRealm());

        RoleRepresentation realmRole = realm.roles()
                .get(role.name())
                .toRepresentation();

        realm.users()
                .get(keycloakId)
                .roles()
                .realmLevel()
                .remove(List.of(realmRole));
    }
}