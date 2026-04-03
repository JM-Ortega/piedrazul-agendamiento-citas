package co.edu.unicauca.piedrazul.backend.user.infrastructure;

import co.edu.unicauca.piedrazul.backend.config.security.KeycloakProperties;
import co.edu.unicauca.piedrazul.backend.shared.auth.Role;
import jakarta.ws.rs.core.Response;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class KeycloakUserClient {

    private final Keycloak keycloak;
    private final KeycloakProperties props;

    public KeycloakUserClient(KeycloakProperties props) {
        this.props = props;
        this.keycloak = KeycloakBuilder.builder()
                .serverUrl(props.getServerUrl())
                .realm(props.getRealm())
                .clientId(props.getClientId())
                .clientSecret(props.getClientSecret())
                .grantType("client_credentials")
                .build();
    }

    public UUID createUser(String username, String firstName, String lastName,
                           String email, String password, Role role) {
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

        Response response = realm.users().create(user);

        if (response.getStatus() == 409) {
            throw new RuntimeException("Usuario ya existe en Keycloak: " + username);
        }

        if (response.getStatus() != 201) {
            throw new RuntimeException("Error al crear usuario en Keycloak. Status: "
                    + response.getStatus() + " - " + response.readEntity(String.class));
        }

        String location = response.getHeaderString("Location");
        String keycloakId = location.substring(location.lastIndexOf('/') + 1);

        assignRealmRole(keycloakId, role);

        return UUID.fromString(keycloakId);
    }

    public Optional<UUID> findUserIdByUsername(String username) {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }

        List<UserRepresentation> users = keycloak.realm(props.getRealm())
                .users()
                .searchByUsername(username, true);

        return users.stream()
                .findFirst()
                .map(UserRepresentation::getId)
                .map(UUID::fromString);
    }

    public void assignRoleIfMissing(UUID keycloakId, Role role) {
        if (!hasRealmRole(keycloakId, role)) {
            assignRealmRole(keycloakId.toString(), role);
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

    private boolean hasRealmRole(UUID keycloakId, Role role) {
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
}