package co.edu.unicauca.piedrazul.backend.user.infrastructure;

import co.edu.unicauca.piedrazul.backend.config.security.KeycloakProperties;
import co.edu.unicauca.piedrazul.backend.shared.enums.Role;
import co.edu.unicauca.piedrazul.backend.user.exception.UserAlreadyExistsException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.*;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KeycloakUserClientTest {

    private static final String REALM = "piedrazul";

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

    private KeycloakUserClient keycloakUserClient;

    @BeforeEach
    void setUp() {
        keycloakUserClient = new KeycloakUserClient(keycloak, props);
    }

    @Test
    void findUserByUsernameShouldReturnEmptyWhenUsernameIsNull() {
        Optional<UserRepresentation> result = keycloakUserClient.findUserByUsername(null);

        assertTrue(result.isEmpty());
        verify(keycloak, never()).realm(any());
    }

    @Test
    void findUserByUsernameShouldReturnEmptyWhenUsernameIsBlank() {
        Optional<UserRepresentation> result = keycloakUserClient.findUserByUsername("   ");

        assertTrue(result.isEmpty());
        verify(keycloak, never()).realm(any());
    }

    @Test
    void findUserByUsernameShouldReturnFirstMatch() {
        mockRealm();

        when(realmResource.users()).thenReturn(usersResource);

        UserRepresentation user = userRepresentation(
                "11111111-1111-1111-1111-111111111111",
                "doctor01",
                "Ana",
                "Lopez",
                "ana@test.com"
        );

        when(usersResource.searchByUsername("doctor01", true)).thenReturn(List.of(user));

        Optional<UserRepresentation> result = keycloakUserClient.findUserByUsername("doctor01");

        assertTrue(result.isPresent());
        assertEquals("doctor01", result.get().getUsername());
        assertEquals("Ana", result.get().getFirstName());
        verify(usersResource).searchByUsername("doctor01", true);
    }

    @Test
    void findUsersByRoleShouldMapMembersFromKeycloak() {
        mockRealm();

        when(realmResource.roles()).thenReturn(rolesResource);
        when(rolesResource.get(Role.DOCTOR.name())).thenReturn(roleResource);

        UserRepresentation firstUser = userRepresentation(
                "22222222-2222-2222-2222-222222222222",
                "doctor01",
                "Ana",
                "Lopez",
                "ana@test.com"
        );

        UserRepresentation secondUser = userRepresentation(
                "33333333-3333-3333-3333-333333333333",
                "doctor02",
                "Luis",
                "Perez",
                "luis@test.com"
        );

        when(roleResource.getUserMembers()).thenReturn(List.of(firstUser, secondUser));

        List<UserRepresentation> result = keycloakUserClient.findUsersByRole(Role.DOCTOR);

        assertEquals(2, result.size());
        assertEquals("doctor01", result.get(0).getUsername());
        assertEquals("doctor02", result.get(1).getUsername());
        verify(rolesResource).get(Role.DOCTOR.name());
    }

    @Test
    void getUserRolesShouldReturnRoleNames() {
        UUID userId = UUID.fromString("44444444-4444-4444-4444-444444444444");
        mockRealmUserRoleScope(userId);

        RoleRepresentation doctorRole = roleRepresentation(Role.DOCTOR);
        RoleRepresentation schedulerRole = roleRepresentation(Role.SCHEDULER);

        when(roleScopeResource.listAll()).thenReturn(List.of(doctorRole, schedulerRole));

        List<String> result = keycloakUserClient.getUserRoles(userId);

        assertEquals(List.of(Role.DOCTOR.name(), Role.SCHEDULER.name()), result);
        verify(roleScopeResource).listAll();
    }

    @Test
    void userHasRoleShouldReturnTrueWhenRoleIsPresent() {
        UUID userId = UUID.fromString("55555555-5555-5555-5555-555555555555");
        mockRealmUserRoleScope(userId);

        RoleRepresentation doctorRole = roleRepresentation(Role.DOCTOR);
        when(roleScopeResource.listAll()).thenReturn(List.of(doctorRole));

        assertTrue(keycloakUserClient.userHasRole(userId, Role.DOCTOR));
    }

    @Test
    void userHasRoleShouldReturnFalseWhenRoleIsMissing() {
        UUID userId = UUID.fromString("66666666-6666-6666-6666-666666666666");
        mockRealmUserRoleScope(userId);

        when(roleScopeResource.listAll()).thenReturn(List.of());

        assertFalse(keycloakUserClient.userHasRole(userId, Role.DOCTOR));
    }

    @Test
    void assignRoleIfMissingShouldAssignWhenUserDoesNotHaveRole() {
        UUID userId = UUID.fromString("77777777-7777-7777-7777-777777777777");
        mockRealmUserRoleScope(userId);

        RoleRepresentation assignedRole = roleRepresentation(Role.SCHEDULER);
        RoleRepresentation doctorRole = roleRepresentation(Role.DOCTOR);

        when(roleScopeResource.listAll()).thenReturn(List.of(assignedRole));
        when(realmResource.roles()).thenReturn(rolesResource);
        when(rolesResource.get(Role.DOCTOR.name())).thenReturn(roleResource);
        when(roleResource.toRepresentation()).thenReturn(doctorRole);

        keycloakUserClient.assignRoleIfMissing(userId, Role.DOCTOR);

        verify(roleScopeResource).add(List.of(doctorRole));
    }

    @Test
    void assignRoleIfMissingShouldNotAssignWhenUserAlreadyHasRole() {
        UUID userId = UUID.fromString("88888888-8888-8888-8888-888888888888");
        mockRealmUserRoleScope(userId);

        RoleRepresentation doctorRole = roleRepresentation(Role.DOCTOR);
        when(roleScopeResource.listAll()).thenReturn(List.of(doctorRole));

        keycloakUserClient.assignRoleIfMissing(userId, Role.DOCTOR);

        verify(roleScopeResource, never()).add(any());
        verify(realmResource, never()).roles();
    }

    @Test
    void revokeRoleIfPresentShouldRevokeWhenUserHasRole() {
        UUID userId = UUID.fromString("99999999-9999-9999-9999-999999999999");
        mockRealmUserRoleScope(userId);

        RoleRepresentation schedulerRole = roleRepresentation(Role.SCHEDULER);
        RoleRepresentation returnedRole = roleRepresentation(Role.SCHEDULER);

        when(roleScopeResource.listAll()).thenReturn(List.of(schedulerRole));
        when(realmResource.roles()).thenReturn(rolesResource);
        when(rolesResource.get(Role.SCHEDULER.name())).thenReturn(roleResource);
        when(roleResource.toRepresentation()).thenReturn(returnedRole);

        keycloakUserClient.revokeRoleIfPresent(userId, Role.SCHEDULER);

        verify(roleScopeResource).remove(List.of(returnedRole));
    }

    @Test
    void revokeRoleIfPresentShouldNotRevokeWhenUserDoesNotHaveRole() {
        UUID userId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        mockRealmUserRoleScope(userId);

        when(roleScopeResource.listAll()).thenReturn(List.of());

        keycloakUserClient.revokeRoleIfPresent(userId, Role.SCHEDULER);

        verify(roleScopeResource, never()).remove(any());
        verify(realmResource, never()).roles();
    }

    @Test
    void existsUserShouldReturnTrueWhenUserExists() {
        UUID userId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        mockRealmUser(userId);

        when(userResource.toRepresentation()).thenReturn(new UserRepresentation());

        boolean result = keycloakUserClient.existsUser(userId);

        assertTrue(result);
        verify(userResource).toRepresentation();
    }

    @Test
    void existsUserShouldReturnFalseWhenKeycloakThrowsException() {
        UUID userId = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
        mockRealmUser(userId);

        when(userResource.toRepresentation()).thenThrow(new RuntimeException("not found"));

        boolean result = keycloakUserClient.existsUser(userId);

        assertFalse(result);
        verify(userResource).toRepresentation();
    }

    /*
    Metodos proximos a eliminar

    @Test
    void activateUserShouldUpdateUserAsEnabled() {
        UUID userId = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
        mockRealmUser(userId);

        keycloakUserClient.activateUser(userId);

        ArgumentCaptor<UserRepresentation> captor =
                ArgumentCaptor.forClass(UserRepresentation.class);

        verify(userResource).update(captor.capture());
        assertTrue(captor.getValue().isEnabled());
    }

    @Test
    void deactivateUserShouldUpdateUserAsDisabled() {
        UUID userId = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");
        mockRealmUser(userId);

        keycloakUserClient.deactivateUser(userId);

        ArgumentCaptor<UserRepresentation> captor =
                ArgumentCaptor.forClass(UserRepresentation.class);

        verify(userResource).update(captor.capture());
        assertFalse(captor.getValue().isEnabled());
    }
     */

    @Test
    void deleteUserShouldDelegateToKeycloak() {
        UUID userId = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        mockRealmUser(userId);

        keycloakUserClient.deleteUser(userId);

        verify(userResource).remove();
    }

    @Test
    void createUserShouldTranslateConflictIntoUserAlreadyExists() {
        mockRealm();
        when(realmResource.users()).thenReturn(usersResource);

        Response conflict = Response.status(Response.Status.CONFLICT).build();
        when(usersResource.create(any(UserRepresentation.class))).thenReturn(conflict);

        assertThrows(UserAlreadyExistsException.class, () -> keycloakUserClient.createUser(
                "1061234567", "Ana", "Ruiz", "ana@example.com", "Secreta123"));

        // No se intenta recuperar ni adoptar la cuenta existente.
        verify(usersResource, never()).searchByUsername(any(), any());
    }

    @Test
    void createUserShouldReturnCreatedUserWithIdFromLocationHeader() {
        mockRealm();
        when(realmResource.users()).thenReturn(usersResource);

        UUID createdId = UUID.randomUUID();
        Response created = Response.status(Response.Status.CREATED)
                .header("Location", "http://kc/admin/realms/" + REALM + "/users/" + createdId)
                .build();
        when(usersResource.create(any(UserRepresentation.class))).thenReturn(created);

        UserRepresentation result = keycloakUserClient.createUser(
                "1061234567", "Ana", "Ruiz", "ana@example.com", "Secreta123");

        assertEquals(createdId.toString(), result.getId());
        assertEquals("1061234567", result.getUsername());
    }

    private void mockRealm() {
        when(props.getRealm()).thenReturn(REALM);
        when(keycloak.realm(REALM)).thenReturn(realmResource);
    }

    private void mockRealmUser(UUID userId) {
        mockRealm();
        when(realmResource.users()).thenReturn(usersResource);
        when(usersResource.get(userId.toString())).thenReturn(userResource);
    }

    private void mockRealmUserRoleScope(UUID userId) {
        mockRealmUser(userId);
        when(userResource.roles()).thenReturn(roleMappingResource);
        when(roleMappingResource.realmLevel()).thenReturn(roleScopeResource);
    }

    private RoleRepresentation roleRepresentation(Role role) {
        RoleRepresentation roleRepresentation = new RoleRepresentation();
        roleRepresentation.setName(role.name());
        return roleRepresentation;
    }

    private UserRepresentation userRepresentation(
            String id,
            String username,
            String firstName,
            String lastName,
            String email
    ) {
        UserRepresentation user = new UserRepresentation();
        user.setId(id);
        user.setUsername(username);
        user.setFirstName(firstName);
        user.setLastName(lastName);
        user.setEmail(email);
        return user;
    }
}