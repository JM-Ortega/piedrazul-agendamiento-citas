package co.edu.unicauca.piedrazul.backend.user.infrastructure;

import co.edu.unicauca.piedrazul.backend.config.security.KeycloakProperties;
import co.edu.unicauca.piedrazul.backend.shared.audit.SecurityContextExtractor;
import co.edu.unicauca.piedrazul.backend.shared.enums.Role;
import co.edu.unicauca.piedrazul.backend.user.events.UserCreatedEvent;
import co.edu.unicauca.piedrazul.backend.user.events.UserRoleAssignedEvent;
import co.edu.unicauca.piedrazul.backend.user.events.UserRoleRevokedEvent;
import co.edu.unicauca.piedrazul.backend.user.exception.IdentityProviderException;
import co.edu.unicauca.piedrazul.backend.user.exception.InvalidUserDataException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.transaction.Transactional;
import co.edu.unicauca.piedrazul.backend.user.exception.UserAlreadyExistsException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class KeycloakUserClient {

    private static final Logger log = LoggerFactory.getLogger(KeycloakUserClient.class);

    private final Keycloak keycloak;
    private final KeycloakProperties props;

    private final ApplicationEventPublisher eventPublisher;
    private final SecurityContextExtractor securityExtractor;
    private final ObjectMapper objectMapper;

    public KeycloakUserClient(Keycloak keycloak, KeycloakProperties props, SecurityContextExtractor securityExtractor,
            ApplicationEventPublisher eventPublisher) {
        this.keycloak = keycloak;
        this.props = props;
        this.securityExtractor = securityExtractor;
        this.eventPublisher = eventPublisher;
        this.objectMapper = new ObjectMapper();
    }

    @Transactional
    public UserRepresentation createUser(
            String username,
            String firstName,
            String lastName,
            String email,
            String password) {
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
                // El conflicto puede ser por username o por email.
                throw new IdentityProviderException(
                        "Ya existe un usuario registrado con ese nombre de usuario o correo electrónico (" + username
                                + " / " + email + ")");
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

        String actorId = securityExtractor.currentActorId();
        String actorRoles = securityExtractor.currentActorRoles();

        eventPublisher.publishEvent(
                UserCreatedEvent.of(
                        keycloakId,
                        actorId,
                        actorRoles,
                        MDC.get("correlationId")));
        return user;
    }

    public List<UserRepresentation> findUsersByRole(Role role) {
        return keycloak.realm(props.getRealm())
                .roles()
                .get(role.name())
                .getUserMembers();
    }

    public List<UserRepresentation> getSystemUsers() {
        List<UserRepresentation> doctors = keycloak.realm(props.getRealm()).roles().get("DOCTOR").getUserMembers();
        List<UserRepresentation> schedulers = keycloak.realm(props.getRealm()).roles().get("SCHEDULER").getUserMembers();

        // Usamos LinkedHashMap para conservar el orden de inserción
        Map<String, UserRepresentation> userMap = new LinkedHashMap<>();

        // Agregamos ambas listas; si un ID ya existe, simplemente se sobreescribe
        for (UserRepresentation user : doctors) {
            userMap.put(user.getId(), user);
        }
        for (UserRepresentation user : schedulers) {
            userMap.put(user.getId(), user);
        }

        return new ArrayList<>(userMap.values());
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

    @Transactional
    public void assignRoleIfMissing(UUID keycloakId, Role role) {
        List<String> before = getUserRoles(keycloakId.toString());

        if (userHasRole(keycloakId, role)) {
            return;
        }

        assignRealmRole(keycloakId.toString(), role);

        List<String> after = getUserRoles(keycloakId.toString());

        eventPublisher.publishEvent(UserRoleAssignedEvent.of(
                keycloakId.toString(),
                securityExtractor.currentActorId(),
                securityExtractor.currentActorRoles(),
                MDC.get("correlationId"),
                toJson(before),
                toJson(after)));
    }

    @Transactional
    public void revokeRoleIfPresent(UUID keycloakId, Role role) {
        List<String> before = getUserRoles(keycloakId.toString());

        if (!userHasRole(keycloakId, role)) {
            return;
        }

        revokeRealmRole(keycloakId.toString(), role);

        List<String> after = getUserRoles(keycloakId.toString());

        eventPublisher.publishEvent(UserRoleRevokedEvent.of(
                keycloakId.toString(),
                securityExtractor.currentActorId(),
                securityExtractor.currentActorRoles(),
                MDC.get("correlationId"),
                toJson(before),
                toJson(after)));
    }

    private String toJson(List<String> roles) {
        try {
            return objectMapper.writeValueAsString(roles);
        } catch (Exception ex) {
            return "[]";
        }
    }

    /**
     * Actualiza username, nombres y correo de una cuenta existente.
     *
     * <p>Cambiar el username exige {@code editUsernameAllowed} en el realm. Sin
     * él, Keycloak 26 rechaza la actualización completa con 400
     * ({@code error-user-attribute-read-only}) y no aplica nada; aquí se traduce a
     * un error que lo dice. Además, si el username cambia se relee la cuenta para
     * comprobar que se aplicó, por si alguna versión lo ignorara en silencio: en
     * ese caso se restauran los demás datos y se falla, para no dejar la cuenta
     * a medias.
     */
    public void updateUser(UUID keycloakId, String username, String firstName, String lastName, String email) {
        UserResource resource = keycloak.realm(props.getRealm())
                .users()
                .get(keycloakId.toString());

        UserRepresentation user;
        try {
            user = resource.toRepresentation();
        } catch (WebApplicationException ex) {
            throw translateUpdateFailure(ex);
        }

        String previousUsername = user.getUsername();
        String previousFirstName = user.getFirstName();
        String previousLastName = user.getLastName();
        String previousEmail = user.getEmail();

        applyIdentity(user, username, firstName, lastName, email);

        try {
            resource.update(user);
        } catch (WebApplicationException ex) {
            throw translateUpdateFailure(ex);
        }

        // Keycloak normaliza el username a minúsculas, por eso no se compara con equals.
        if (username.equalsIgnoreCase(previousUsername)) {
            return;
        }

        String appliedUsername = resource.toRepresentation().getUsername();
        if (!username.equalsIgnoreCase(appliedUsername)) {
            try {
                applyIdentity(user, previousUsername, previousFirstName, previousLastName, previousEmail);
                resource.update(user);
            } catch (RuntimeException restoreFailure) {
                log.error("No se pudo restaurar la cuenta {} tras un cambio de username no aplicado",
                        keycloakId, restoreFailure);
            }
            throw new IdentityProviderException(
                    "el proveedor no aplicó el cambio de nombre de usuario; "
                            + "verifique que el realm tenga habilitada la edición de username");
        }
    }

    private static void applyIdentity(
            UserRepresentation user, String username, String firstName, String lastName, String email) {
        user.setUsername(username);
        user.setFirstName(firstName != null ? firstName : "");
        user.setLastName(lastName != null ? lastName : "");
        user.setEmail(email != null ? email : "");
    }

    private static String readBody(WebApplicationException ex) {
        try {
            return ex.getResponse().hasEntity() ? ex.getResponse().readEntity(String.class) : "";
        } catch (RuntimeException readFailure) {
            return "";
        }
    }

    private RuntimeException translateUpdateFailure(WebApplicationException ex) {
        int status = ex.getResponse().getStatus();

        if (status == Response.Status.NOT_FOUND.getStatusCode()) {
            return new IdentityProviderException("la cuenta de usuario no existe en el proveedor de identidad");
        }

        if (status == Response.Status.CONFLICT.getStatusCode()) {
            // El conflicto puede ser por username o por correo.
            return new UserAlreadyExistsException(
                    "Ya existe otra cuenta con ese nombre de usuario o correo electrónico");
        }

        if (status == Response.Status.BAD_REQUEST.getStatusCode()) {
            String body = readBody(ex);

            if (body.contains("error-user-attribute-read-only") && body.contains("username")) {
                log.error("Keycloak rechazó el cambio de username: el realm no tiene editUsernameAllowed");
                return new IdentityProviderException(
                        "el proveedor de identidad no permite modificar el nombre de usuario; "
                                + "habilite la edición de username en el realm");
            }

            log.warn("Datos inválidos en Keycloak al actualizar usuario: {}", body);
            return new InvalidUserDataException("Datos inválidos para actualizar el usuario");
        }

        log.error("Error Keycloak al actualizar usuario", ex);
        return new IdentityProviderException("No se pudo actualizar el usuario");
    }

    /**
     * Fija la contraseña vigente de una cuenta existente. Uso interno del módulo:
     * no se expone en ninguna API pública, para que los consumidores pidan
     * capacidades de negocio y no mecánica de Keycloak.
     */
    public void resetPassword(UUID keycloakId, String password) {
        CredentialRepresentation credential = new CredentialRepresentation();
        credential.setType(CredentialRepresentation.PASSWORD);
        credential.setValue(password);
        credential.setTemporary(false);

        keycloak.realm(props.getRealm())
                .users()
                .get(keycloakId.toString())
                .resetPassword(credential);
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

    public List<String> getUserRoles(String keycloakId) {
        return keycloak.realm(props.getRealm())
                .users()
                .get(keycloakId)
                .roles()
                .realmLevel()
                .listAll()
                .stream()
                .map(RoleRepresentation::getName)
                .toList();
    }

    public Map<UUID, List<String>> getUserRolesByIds(Collection<UUID> keycloakIds) {
        Map<UUID, List<String>> rolesByUserId = new LinkedHashMap<>();

        for (UUID keycloakId : keycloakIds) {
            rolesByUserId.put(keycloakId, getUserRoles(keycloakId.toString()));
        }

        return rolesByUserId;
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