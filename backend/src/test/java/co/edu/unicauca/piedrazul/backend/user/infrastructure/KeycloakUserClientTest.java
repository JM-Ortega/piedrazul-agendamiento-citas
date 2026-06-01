package co.edu.unicauca.piedrazul.backend.user.infrastructure;

import co.edu.unicauca.piedrazul.backend.config.security.KeycloakProperties;
import co.edu.unicauca.piedrazul.backend.shared.auth.Role;
import co.edu.unicauca.piedrazul.backend.user.exception.IdentityProviderException;
import co.edu.unicauca.piedrazul.backend.user.exception.InvalidUserDataException;
import co.edu.unicauca.piedrazul.backend.user.exception.UserAlreadyExistsException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.*;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KeycloakUserClientTest {

    @Mock
    private Keycloak keycloak;

    @Mock
    private KeycloakProperties props;

    @Mock
    private RealmResource realmResource;

    @Mock
    private UsersResource usersResource;

    @Mock
    private RolesResource rolesResource;

    @Mock
    private RoleResource roleResource;

    @Mock
    private UserResource userResource;

    @Mock
    private RoleMappingResource roleMappingResource;

    @Mock
    private RoleScopeResource roleScopeResource;

    @Mock
    private Response response;

    @InjectMocks
    private KeycloakUserClient keycloakUserClient;

    @Test
    void createUser_shouldThrowUserAlreadyExistsException_whenKeycloakReturnsConflict() {
        when(props.getRealm()).thenReturn("piedrazul");
        when(keycloak.realm("piedrazul")).thenReturn(realmResource);
        when(realmResource.users()).thenReturn(usersResource);
        when(usersResource.create(any(UserRepresentation.class))).thenReturn(response);
        when(response.getStatus()).thenReturn(Response.Status.CONFLICT.getStatusCode());

        assertThrows(
                UserAlreadyExistsException.class,
                () -> keycloakUserClient.createUser(
                        "123456789",
                        "Juan",
                        "Perez",
                        "juan@test.com",
                        "secret",
                        Role.PATIENT
                )
        );

        verify(response).close();
        verify(realmResource, never()).roles();
    }

    @Test
    void createUser_shouldThrowInvalidUserDataException_whenKeycloakReturnsBadRequest() {
        when(props.getRealm()).thenReturn("piedrazul");
        when(keycloak.realm("piedrazul")).thenReturn(realmResource);
        when(realmResource.users()).thenReturn(usersResource);
        when(usersResource.create(any(UserRepresentation.class))).thenReturn(response);
        when(response.getStatus()).thenReturn(Response.Status.BAD_REQUEST.getStatusCode());
        when(response.hasEntity()).thenReturn(true);
        when(response.readEntity(String.class)).thenReturn("invalid payload");

        assertThrows(
                InvalidUserDataException.class,
                () -> keycloakUserClient.createUser(
                        "123456789",
                        "Juan",
                        "Perez",
                        "juan@test.com",
                        "secret",
                        Role.PATIENT
                )
        );

        verify(response).close();
        verify(realmResource, never()).roles();
    }

    @Test
    void createUser_shouldThrowIdentityProviderException_whenStatusIsUnexpected() {
        when(props.getRealm()).thenReturn("piedrazul");
        when(keycloak.realm("piedrazul")).thenReturn(realmResource);
        when(realmResource.users()).thenReturn(usersResource);
        when(usersResource.create(any(UserRepresentation.class))).thenReturn(response);
        when(response.getStatus()).thenReturn(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode());
        when(response.hasEntity()).thenReturn(true);
        when(response.readEntity(String.class)).thenReturn("server error");

        assertThrows(
                IdentityProviderException.class,
                () -> keycloakUserClient.createUser(
                        "123456789",
                        "Juan",
                        "Perez",
                        "juan@test.com",
                        "secret",
                        Role.PATIENT
                )
        );

        verify(response).close();
        verify(realmResource, never()).roles();
    }

    @Test
    void createUser_shouldThrowIdentityProviderException_whenLocationHeaderIsMissing() {
        when(props.getRealm()).thenReturn("piedrazul");
        when(keycloak.realm("piedrazul")).thenReturn(realmResource);
        when(realmResource.users()).thenReturn(usersResource);
        when(usersResource.create(any(UserRepresentation.class))).thenReturn(response);
        when(response.getStatus()).thenReturn(Response.Status.CREATED.getStatusCode());
        when(response.getHeaderString("Location")).thenReturn(null);

        assertThrows(
                IdentityProviderException.class,
                () -> keycloakUserClient.createUser(
                        "123456789",
                        "Juan",
                        "Perez",
                        "juan@test.com",
                        "secret",
                        Role.PATIENT
                )
        );

        verify(response).close();
        verify(realmResource, never()).roles();
    }

    @Test
    void createUser_shouldCreateUserAndAssignRealmRole_whenResponseIsCreated() {
        when(props.getRealm()).thenReturn("piedrazul");
        when(keycloak.realm("piedrazul")).thenReturn(realmResource);

        UUID createdId = UUID.randomUUID();
        String location = "http://localhost/admin/realms/piedrazul/users/" + createdId;

        RoleRepresentation patientRole = new RoleRepresentation();
        patientRole.setName(Role.PATIENT.name());

        when(realmResource.users()).thenReturn(usersResource);
        when(usersResource.create(any(UserRepresentation.class))).thenReturn(response);
        when(response.getStatus()).thenReturn(Response.Status.CREATED.getStatusCode());
        when(response.getHeaderString("Location")).thenReturn(location);

        when(realmResource.roles()).thenReturn(rolesResource);
        when(rolesResource.get(Role.PATIENT.name())).thenReturn(roleResource);
        when(roleResource.toRepresentation()).thenReturn(patientRole);

        when(usersResource.get(createdId.toString())).thenReturn(userResource);
        when(userResource.roles()).thenReturn(roleMappingResource);
        when(roleMappingResource.realmLevel()).thenReturn(roleScopeResource);

        UUID result = keycloakUserClient.createUser(
                "123456789",
                "Juan",
                "Perez",
                "juan@test.com",
                "secret",
                Role.PATIENT
        );

        assertEquals(createdId, result);

        ArgumentCaptor<UserRepresentation> userCaptor =
                ArgumentCaptor.forClass(UserRepresentation.class);

        verify(usersResource).create(userCaptor.capture());

        UserRepresentation createdUser = userCaptor.getValue();
        assertEquals("123456789", createdUser.getUsername());
        assertEquals("Juan", createdUser.getFirstName());
        assertEquals("Perez", createdUser.getLastName());
        assertEquals("juan@test.com", createdUser.getEmail());
        assertTrue(createdUser.isEnabled());
        assertTrue(createdUser.isEmailVerified());
        assertNotNull(createdUser.getCredentials());
        assertEquals(1, createdUser.getCredentials().size());
        assertEquals("secret", createdUser.getCredentials().get(0).getValue());

        verify(roleScopeResource).add(List.of(patientRole));
        verify(response).close();
    }

    @Test
    void findUserIdByUsername_shouldReturnEmpty_whenUsernameIsNull() {
        Optional<UUID> result = keycloakUserClient.findUserIdByUsername(null);

        assertTrue(result.isEmpty());
        verify(keycloak, never()).realm(anyString());
    }

    @Test
    void findUserIdByUsername_shouldReturnEmpty_whenUsernameIsBlank() {
        Optional<UUID> result = keycloakUserClient.findUserIdByUsername("   ");

        assertTrue(result.isEmpty());
        verify(keycloak, never()).realm(anyString());
    }

    @Test
    void findUserIdByUsername_shouldReturnEmpty_whenNoUsersAreFound() {
        when(props.getRealm()).thenReturn("piedrazul");
        when(keycloak.realm("piedrazul")).thenReturn(realmResource);
        when(realmResource.users()).thenReturn(usersResource);
        when(usersResource.searchByUsername("123456789", true)).thenReturn(List.of());

        Optional<UUID> result = keycloakUserClient.findUserIdByUsername("123456789");

        assertTrue(result.isEmpty());
        verify(usersResource).searchByUsername("123456789", true);
    }

    @Test
    void findUserIdByUsername_shouldReturnUserId_whenUserExists() {
        when(props.getRealm()).thenReturn("piedrazul");
        when(keycloak.realm("piedrazul")).thenReturn(realmResource);

        UUID userId = UUID.randomUUID();
        UserRepresentation user = new UserRepresentation();
        user.setId(userId.toString());

        when(realmResource.users()).thenReturn(usersResource);
        when(usersResource.searchByUsername("123456789", true)).thenReturn(List.of(user));

        Optional<UUID> result = keycloakUserClient.findUserIdByUsername("123456789");

        assertTrue(result.isPresent());
        assertEquals(userId, result.get());
        verify(usersResource).searchByUsername("123456789", true);
    }

    @Test
    void existsUser_shouldReturnTrue_whenUserExists() {
        when(props.getRealm()).thenReturn("piedrazul");
        when(keycloak.realm("piedrazul")).thenReturn(realmResource);

        UUID userId = UUID.randomUUID();

        when(realmResource.users()).thenReturn(usersResource);
        when(usersResource.get(userId.toString())).thenReturn(userResource);
        when(userResource.toRepresentation()).thenReturn(new UserRepresentation());

        boolean result = keycloakUserClient.existsUser(userId);

        assertTrue(result);
        verify(userResource).toRepresentation();
    }

    @Test
    void existsUser_shouldReturnFalse_whenKeycloakThrowsException() {
        when(props.getRealm()).thenReturn("piedrazul");
        when(keycloak.realm("piedrazul")).thenReturn(realmResource);

        UUID userId = UUID.randomUUID();

        when(realmResource.users()).thenReturn(usersResource);
        when(usersResource.get(userId.toString())).thenReturn(userResource);
        when(userResource.toRepresentation()).thenThrow(new RuntimeException("not found"));

        boolean result = keycloakUserClient.existsUser(userId);

        assertFalse(result);
        verify(userResource).toRepresentation();
    }

    @Test
    void activateUser_shouldUpdateUserAsEnabled() {
        when(props.getRealm()).thenReturn("piedrazul");
        when(keycloak.realm("piedrazul")).thenReturn(realmResource);

        UUID userId = UUID.randomUUID();

        when(realmResource.users()).thenReturn(usersResource);
        when(usersResource.get(userId.toString())).thenReturn(userResource);

        keycloakUserClient.activateUser(userId);

        ArgumentCaptor<UserRepresentation> userCaptor =
                ArgumentCaptor.forClass(UserRepresentation.class);

        verify(userResource).update(userCaptor.capture());
        assertTrue(userCaptor.getValue().isEnabled());
    }

    @Test
    void deactivateUser_shouldUpdateUserAsDisabled() {
        when(props.getRealm()).thenReturn("piedrazul");
        when(keycloak.realm("piedrazul")).thenReturn(realmResource);

        UUID userId = UUID.randomUUID();

        when(realmResource.users()).thenReturn(usersResource);
        when(usersResource.get(userId.toString())).thenReturn(userResource);

        keycloakUserClient.deactivateUser(userId);

        ArgumentCaptor<UserRepresentation> userCaptor =
                ArgumentCaptor.forClass(UserRepresentation.class);

        verify(userResource).update(userCaptor.capture());
        assertFalse(userCaptor.getValue().isEnabled());
    }

    @Test
    void assignRoleIfMissing_shouldAssignRole_whenUserDoesNotHaveRole() {
        when(props.getRealm()).thenReturn("piedrazul");
        when(keycloak.realm("piedrazul")).thenReturn(realmResource);

        UUID userId = UUID.randomUUID();

        RoleRepresentation otherRole = new RoleRepresentation();
        otherRole.setName(Role.DOCTOR.name());

        RoleRepresentation patientRole = new RoleRepresentation();
        patientRole.setName(Role.PATIENT.name());

        when(realmResource.users()).thenReturn(usersResource);
        when(usersResource.get(userId.toString())).thenReturn(userResource);
        when(userResource.roles()).thenReturn(roleMappingResource);
        when(roleMappingResource.realmLevel()).thenReturn(roleScopeResource);
        when(roleScopeResource.listAll()).thenReturn(List.of(otherRole));

        when(realmResource.roles()).thenReturn(rolesResource);
        when(rolesResource.get(Role.PATIENT.name())).thenReturn(roleResource);
        when(roleResource.toRepresentation()).thenReturn(patientRole);

        keycloakUserClient.assignRoleIfMissing(userId, Role.PATIENT);

        verify(roleScopeResource).add(List.of(patientRole));
    }

    @Test
    void assignRoleIfMissing_shouldNotAssignRole_whenUserAlreadyHasRole() {
        when(props.getRealm()).thenReturn("piedrazul");
        when(keycloak.realm("piedrazul")).thenReturn(realmResource);

        UUID userId = UUID.randomUUID();

        RoleRepresentation patientRole = new RoleRepresentation();
        patientRole.setName(Role.PATIENT.name());

        when(realmResource.users()).thenReturn(usersResource);
        when(usersResource.get(userId.toString())).thenReturn(userResource);
        when(userResource.roles()).thenReturn(roleMappingResource);
        when(roleMappingResource.realmLevel()).thenReturn(roleScopeResource);
        when(roleScopeResource.listAll()).thenReturn(List.of(patientRole));

        keycloakUserClient.assignRoleIfMissing(userId, Role.PATIENT);

        verify(roleScopeResource, never()).add(anyList());
        verify(realmResource, never()).roles();
    }
}