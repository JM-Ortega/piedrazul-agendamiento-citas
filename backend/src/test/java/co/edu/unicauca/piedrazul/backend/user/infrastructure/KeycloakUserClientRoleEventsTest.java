package co.edu.unicauca.piedrazul.backend.user.infrastructure;

import co.edu.unicauca.piedrazul.backend.config.security.KeycloakProperties;
import co.edu.unicauca.piedrazul.backend.shared.audit.SecurityContextExtractor;
import co.edu.unicauca.piedrazul.backend.shared.enums.Role;
import co.edu.unicauca.piedrazul.backend.user.events.UserRoleAssignedEvent;
import co.edu.unicauca.piedrazul.backend.user.events.UserRoleRevokedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.RoleMappingResource;
import org.keycloak.admin.client.resource.RoleResource;
import org.keycloak.admin.client.resource.RoleScopeResource;
import org.keycloak.admin.client.resource.RolesResource;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.admin.client.resource.UsersResource;
import org.keycloak.representations.idm.RoleRepresentation;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Asignar o revocar un rol se audita como cambio de roles, no como activación o
 * desactivación de la cuenta.
 */
@ExtendWith(MockitoExtension.class)
class KeycloakUserClientRoleEventsTest {

    private static final UUID USER_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @Mock
    private Keycloak keycloak;
    @Mock
    private KeycloakProperties props;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private SecurityContextExtractor securityExtractor;
    @Mock
    private RealmResource realmResource;
    @Mock
    private UsersResource usersResource;
    @Mock
    private UserResource userResource;
    @Mock
    private RolesResource rolesResource;
    @Mock
    private RoleResource roleResource;
    @Mock
    private RoleMappingResource roleMappingResource;
    @Mock
    private RoleScopeResource roleScopeResource;

    private KeycloakUserClient client;

    @BeforeEach
    void setUp() {
        client = new KeycloakUserClient(keycloak, props, securityExtractor, eventPublisher);
        when(props.getRealm()).thenReturn("piedrazul");
        when(keycloak.realm("piedrazul")).thenReturn(realmResource);
        when(realmResource.users()).thenReturn(usersResource);
        when(usersResource.get(USER_ID.toString())).thenReturn(userResource);
        when(userResource.roles()).thenReturn(roleMappingResource);
        when(roleMappingResource.realmLevel()).thenReturn(roleScopeResource);
        when(realmResource.roles()).thenReturn(rolesResource);
        when(rolesResource.get(Role.DOCTOR.name())).thenReturn(roleResource);
        when(securityExtractor.currentActorId()).thenReturn("admin-1");
        when(securityExtractor.currentActorRoles()).thenReturn("[ADMIN]");
    }

    private static RoleRepresentation role(Role role) {
        RoleRepresentation representation = new RoleRepresentation();
        representation.setName(role.name());
        return representation;
    }

    private Object publishedEvent() {
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(event.capture());
        return event.getValue();
    }

    @Test
    void assigningARolePublishesARoleAssignedEventWithTheRolesBeforeAndAfter() {
        RoleRepresentation doctor = role(Role.DOCTOR);
        // antes (para el evento), comprobación de si ya lo tiene, después (para el evento)
        when(roleScopeResource.listAll()).thenReturn(
                List.of(role(Role.SCHEDULER)), List.of(role(Role.SCHEDULER)),
                List.of(role(Role.SCHEDULER), doctor));
        when(roleResource.toRepresentation()).thenReturn(doctor);

        client.assignRoleIfMissing(USER_ID, Role.DOCTOR);

        Object published = publishedEvent();
        assertThat(published).isInstanceOf(UserRoleAssignedEvent.class);
        UserRoleAssignedEvent event = (UserRoleAssignedEvent) published;
        assertThat(event.userId()).isEqualTo(USER_ID.toString());
        assertThat(event.performedBy()).isEqualTo("admin-1");
        assertThat(event.rolesBefore()).isEqualTo("[\"SCHEDULER\"]");
        assertThat(event.rolesAfter()).isEqualTo("[\"SCHEDULER\",\"DOCTOR\"]");
    }

    @Test
    void revokingARolePublishesARoleRevokedEventWithTheRolesBeforeAndAfter() {
        RoleRepresentation doctor = role(Role.DOCTOR);
        when(roleScopeResource.listAll()).thenReturn(
                List.of(role(Role.SCHEDULER), doctor), List.of(role(Role.SCHEDULER), doctor),
                List.of(role(Role.SCHEDULER)));
        when(roleResource.toRepresentation()).thenReturn(doctor);

        client.revokeRoleIfPresent(USER_ID, Role.DOCTOR);

        Object published = publishedEvent();
        assertThat(published).isInstanceOf(UserRoleRevokedEvent.class);
        UserRoleRevokedEvent event = (UserRoleRevokedEvent) published;
        assertThat(event.rolesBefore()).isEqualTo("[\"SCHEDULER\",\"DOCTOR\"]");
        assertThat(event.rolesAfter()).isEqualTo("[\"SCHEDULER\"]");
    }
}
