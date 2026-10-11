package co.edu.unicauca.piedrazul.backend.user.infrastructure;

import co.edu.unicauca.piedrazul.backend.config.security.KeycloakProperties;
import co.edu.unicauca.piedrazul.backend.shared.audit.SecurityContextExtractor;
import co.edu.unicauca.piedrazul.backend.user.events.UserCreatedEvent;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.admin.client.resource.UsersResource;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Creación de la cuenta del bootstrap: forma exacta de la representación y clasificación
 * de la respuesta sin suponer más de lo que Keycloak prueba.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KeycloakUserClientBootstrapTest {

    private static final UUID ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @Mock
    private Keycloak keycloak;
    @Mock
    private KeycloakProperties props;
    @Mock
    private SecurityContextExtractor securityExtractor;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private RealmResource realm;
    @Mock
    private UsersResource users;

    private KeycloakUserClient client;

    @BeforeEach
    void setUp() {
        when(props.getRealm()).thenReturn("piedrazul");
        when(keycloak.realm("piedrazul")).thenReturn(realm);
        when(realm.users()).thenReturn(users);
        client = new KeycloakUserClient(keycloak, props, securityExtractor, eventPublisher);
    }

    private static Response response(int status, String location) {
        Response response = mock(Response.class);
        when(response.getStatus()).thenReturn(status);
        when(response.getHeaderString("Location")).thenReturn(location);
        return response;
    }

    private AccountCreationResult create() {
        return client.createDisabledUserWithTemporaryPassword("1061234567", "Ana", "Prueba", "ana@example.test", "Temp-Bootstrap-Value-2026!");
    }

    @Test
    void createsDisabledUnverifiedAccountWithTemporaryPasswordAndUpdatePassword() {
        Response response = response(201, "http://kc/admin/realms/piedrazul/users/" + ID);
        when(users.create(any())).thenReturn(response);

        AccountCreationResult result = create();

        assertThat(result).isEqualTo(new AccountCreationResult.Created(ID));
        ArgumentCaptor<UserRepresentation> captor = ArgumentCaptor.forClass(UserRepresentation.class);
        verify(users).create(captor.capture());
        UserRepresentation sent = captor.getValue();
        assertThat(sent.isEnabled()).isFalse();
        assertThat(sent.isEmailVerified()).isFalse();
        assertThat(sent.getRequiredActions()).containsExactly("UPDATE_PASSWORD");
        assertThat(sent.getRealmRoles()).isNull();
        CredentialRepresentation credential = sent.getCredentials().getFirst();
        assertThat(credential.isTemporary()).isTrue();
        assertThat(credential.getType()).isEqualTo(CredentialRepresentation.PASSWORD);
        assertThat(credential.getValue()).isEqualTo("Temp-Bootstrap-Value-2026!");
        verify(eventPublisher).publishEvent(any(UserCreatedEvent.class));
    }

    @Test
    void conflictMeansNothingWasCreated() {
        Response response = response(409, null);
        when(users.create(any())).thenReturn(response);

        assertThat(create()).isInstanceOf(AccountCreationResult.Conflict.class);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void clientErrorIsARejection() {
        Response response = response(400, null);
        when(users.create(any())).thenReturn(response);

        assertThat(create()).isEqualTo(new AccountCreationResult.Rejected(400));
    }

    @Test
    void serverErrorIsUncertain() {
        Response response = response(502, null);
        when(users.create(any())).thenReturn(response);

        assertThat(create()).isEqualTo(new AccountCreationResult.Uncertain("status_502"));
    }

    @Test
    void createdWithoutReadableIdIsUncertain() {
        Response response = response(201, null);
        when(users.create(any())).thenReturn(response);

        assertThat(create()).isEqualTo(new AccountCreationResult.Uncertain("created_without_readable_id"));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void transportFailureIsUncertain() {
        when(users.create(any())).thenThrow(new ProcessingException("read timed out"));

        assertThat(create()).isEqualTo(new AccountCreationResult.Uncertain("ProcessingException"));
    }

    @Test
    void findUserByIdTreatsOnly404AsAbsent() {
        UserResource resource = mock(UserResource.class);
        when(users.get(ID.toString())).thenReturn(resource);
        doThrow(new NotFoundException()).when(resource).toRepresentation();

        assertThat(client.findUserById(ID)).isEmpty();

        doThrow(new ProcessingException("timeout")).when(resource).toRepresentation();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> client.findUserById(ID))
                .isInstanceOf(ProcessingException.class);
    }

    @Test
    void enableUserOnlyFlipsTheEnabledFlag() {
        UserResource resource = mock(UserResource.class);
        UserRepresentation current = new UserRepresentation();
        current.setEnabled(false);
        current.setRequiredActions(List.of("UPDATE_PASSWORD"));
        when(users.get(ID.toString())).thenReturn(resource);
        when(resource.toRepresentation()).thenReturn(current);

        client.enableUser(ID);

        ArgumentCaptor<UserRepresentation> captor = ArgumentCaptor.forClass(UserRepresentation.class);
        verify(resource).update(captor.capture());
        assertThat(captor.getValue().isEnabled()).isTrue();
        assertThat(captor.getValue().getRequiredActions()).containsExactly("UPDATE_PASSWORD");
        verify(resource, never()).resetPassword(any());
    }
}
