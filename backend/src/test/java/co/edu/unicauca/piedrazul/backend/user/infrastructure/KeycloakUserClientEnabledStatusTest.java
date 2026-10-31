package co.edu.unicauca.piedrazul.backend.user.infrastructure;

import co.edu.unicauca.piedrazul.backend.config.security.KeycloakProperties;
import co.edu.unicauca.piedrazul.backend.shared.audit.SecurityContextExtractor;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.admin.client.resource.UsersResource;
import org.keycloak.representations.idm.UserRepresentation;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KeycloakUserClientEnabledStatusTest {

    private static final UUID ENABLED_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DISABLED_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID MISSING_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

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

    private KeycloakUserClient client;

    @BeforeEach
    void setUp() {
        client = new KeycloakUserClient(keycloak, props, securityExtractor, eventPublisher);
        // lenient: el test de la colección vacía nunca llega a usar estos stubs.
        lenient().when(props.getRealm()).thenReturn("piedrazul");
        lenient().when(keycloak.realm("piedrazul")).thenReturn(realmResource);
        lenient().when(realmResource.users()).thenReturn(usersResource);
    }

    private static UserResource accountResource(Boolean enabled) {
        UserResource resource = mock(UserResource.class);
        UserRepresentation user = new UserRepresentation();
        user.setEnabled(enabled);
        when(resource.toRepresentation()).thenReturn(user);
        return resource;
    }

    @Test
    void reportsTheEnabledFlagOfEachAccount() {
        UserResource enabled = accountResource(true);
        UserResource disabled = accountResource(false);
        when(usersResource.get(ENABLED_ID.toString())).thenReturn(enabled);
        when(usersResource.get(DISABLED_ID.toString())).thenReturn(disabled);

        Map<UUID, Boolean> result = client.getEnabledStatusByIds(List.of(ENABLED_ID, DISABLED_ID));

        assertThat(result).containsEntry(ENABLED_ID, true).containsEntry(DISABLED_ID, false);
    }

    @Test
    void anAccountWithoutTheEnabledFlagCountsAsDisabled() {
        UserResource withoutFlag = accountResource(null);
        when(usersResource.get(ENABLED_ID.toString())).thenReturn(withoutFlag);

        Map<UUID, Boolean> result = client.getEnabledStatusByIds(List.of(ENABLED_ID));

        assertThat(result).containsEntry(ENABLED_ID, false);
    }

    @Test
    void anAccountThatNoLongerExistsIsReportedAsDisabledInsteadOfFailingTheWholeBatch() {
        // El código no inspecciona el estado de la respuesta para esta consulta: cualquier
        // error del proveedor basta para reportar la cuenta como desactivada.
        UserResource missing = mock(UserResource.class);
        when(missing.toRepresentation()).thenThrow(new WebApplicationException("not found", mock(Response.class)));
        UserResource enabled = accountResource(true);
        when(usersResource.get(MISSING_ID.toString())).thenReturn(missing);
        when(usersResource.get(ENABLED_ID.toString())).thenReturn(enabled);

        Map<UUID, Boolean> result = client.getEnabledStatusByIds(List.of(MISSING_ID, ENABLED_ID));

        assertThat(result).containsEntry(MISSING_ID, false).containsEntry(ENABLED_ID, true);
    }

    @Test
    void anEmptyCollectionYieldsAnEmptyMapWithoutCallingKeycloak() {
        Map<UUID, Boolean> result = client.getEnabledStatusByIds(List.of());

        assertThat(result).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(usersResource);
    }
}
