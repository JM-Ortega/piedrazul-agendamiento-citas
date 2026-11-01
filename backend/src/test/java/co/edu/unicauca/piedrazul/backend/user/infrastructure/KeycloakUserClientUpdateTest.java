package co.edu.unicauca.piedrazul.backend.user.infrastructure;

import co.edu.unicauca.piedrazul.backend.config.security.KeycloakProperties;
import co.edu.unicauca.piedrazul.backend.shared.audit.SecurityContextExtractor;
import co.edu.unicauca.piedrazul.backend.user.exception.IdentityProviderException;
import co.edu.unicauca.piedrazul.backend.user.exception.InvalidUserDataException;
import co.edu.unicauca.piedrazul.backend.user.exception.UserAlreadyExistsException;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class hubKeycloakUserClientUpdateTest {

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

    private KeycloakUserClient client;

    @BeforeEach
    void setUp() {
        client = new KeycloakUserClient(keycloak, props, securityExtractor, eventPublisher);
        when(props.getRealm()).thenReturn("piedrazul");
        when(keycloak.realm("piedrazul")).thenReturn(realmResource);
        when(realmResource.users()).thenReturn(usersResource);
        when(usersResource.get(USER_ID.toString())).thenReturn(userResource);
    }

    private static UserRepresentation account(String username, String first, String last, String email) {
        UserRepresentation user = new UserRepresentation();
        user.setUsername(username);
        user.setFirstName(first);
        user.setLastName(last);
        user.setEmail(email);
        return user;
    }

    private static WebApplicationException failure(int status, String body) {
        Response response = mock(Response.class);
        when(response.getStatus()).thenReturn(status);
        if (body != null) {
            when(response.hasEntity()).thenReturn(true);
            when(response.readEntity(String.class)).thenReturn(body);
        }
        return new WebApplicationException("keycloak " + status, response);
    }

    @Test
    void updateSendsUsernameNamesAndEmailAndConfirmsTheUsernameWasApplied() {
        when(userResource.toRepresentation()).thenReturn(
                account("1002003004", "Ana", "Ruiz", "ana@example.com"),
                account("9998887776", "Anabel", "Ruiz", "nuevo@example.com"));

        client.updateUser(USER_ID, "9998887776", "Anabel", "Ruiz", "nuevo@example.com");

        ArgumentCaptor<UserRepresentation> sent = ArgumentCaptor.forClass(UserRepresentation.class);
        verify(userResource).update(sent.capture());
        assertThat(sent.getValue().getUsername()).isEqualTo("9998887776");
        assertThat(sent.getValue().getFirstName()).isEqualTo("Anabel");
        assertThat(sent.getValue().getLastName()).isEqualTo("Ruiz");
        assertThat(sent.getValue().getEmail()).isEqualTo("nuevo@example.com");
    }

    @Test
    void nullEmailIsSentAsEmptyLikeWhenTheAccountIsCreated() {
        when(userResource.toRepresentation()).thenReturn(account("1002003004", "Ana", "Ruiz", "ana@example.com"));

        client.updateUser(USER_ID, "1002003004", "Ana", "Ruiz", null);

        ArgumentCaptor<UserRepresentation> sent = ArgumentCaptor.forClass(UserRepresentation.class);
        verify(userResource).update(sent.capture());
        assertThat(sent.getValue().getEmail()).isEmpty();
    }

    @Test
    void unchangedUsernameIsNotReReadBecauseThereIsNothingToConfirm() {
        when(userResource.toRepresentation()).thenReturn(account("1002003004", "Ana", "Ruiz", "ana@example.com"));

        client.updateUser(USER_ID, "1002003004", "Anabel", "Ruiz", "ana@example.com");

        verify(userResource, times(1)).toRepresentation();
        verify(userResource, times(1)).update(any());
    }

    @Test
    void keycloakStoresUsernamesInLowercaseSoADifferentCaseIsNotAChange() {
        // Un pasaporte "AB123456" queda como "ab123456" en Keycloak.
        when(userResource.toRepresentation()).thenReturn(account("ab123456", "Ana", "Ruiz", "ana@example.com"));

        client.updateUser(USER_ID, "AB123456", "Ana", "Ruiz", "ana@example.com");

        verify(userResource, times(1)).toRepresentation();
    }

    @Test
    void aUsernameThatKeycloakSilentlyIgnoredRestoresTheOtherDataAndFails() {
        when(userResource.toRepresentation()).thenReturn(
                account("1002003004", "Ana", "Ruiz", "ana@example.com"),
                account("1002003004", "Anabel", "Ruiz", "ana@example.com")); // el username no cambió

        assertThatThrownBy(() -> client.updateUser(USER_ID, "9998887776", "Anabel", "Ruiz", "ana@example.com"))
                .isInstanceOf(IdentityProviderException.class)
                .hasMessageContaining("nombre de usuario");

        ArgumentCaptor<UserRepresentation> sent = ArgumentCaptor.forClass(UserRepresentation.class);
        verify(userResource, times(2)).update(sent.capture());
        UserRepresentation restore = sent.getAllValues().get(1);
        assertThat(restore.getUsername()).isEqualTo("1002003004");
        assertThat(restore.getFirstName()).isEqualTo("Ana");
    }

    @Test
    void readOnlyUsernameRejectionExplainsTheRealmMustAllowEditingIt() {
        when(userResource.toRepresentation()).thenReturn(account("1002003004", "Ana", "Ruiz", "ana@example.com"));
        doThrow(failure(400, "{\"field\":\"username\",\"errorMessage\":\"error-user-attribute-read-only\"}"))
                .when(userResource).update(any());

        assertThatThrownBy(() -> client.updateUser(USER_ID, "9998887776", "Ana", "Ruiz", "ana@example.com"))
                .isInstanceOf(IdentityProviderException.class)
                .hasMessageContaining("nombre de usuario")
                .hasMessageContaining("realm");
    }

    @Test
    void otherBadRequestsAreInvalidData() {
        when(userResource.toRepresentation()).thenReturn(account("1002003004", "Ana", "Ruiz", "ana@example.com"));
        doThrow(failure(400, "{\"field\":\"email\",\"errorMessage\":\"error-invalid-email\"}"))
                .when(userResource).update(any());

        assertThatThrownBy(() -> client.updateUser(USER_ID, "1002003004", "Ana", "Ruiz", "no-es-correo"))
                .isInstanceOf(InvalidUserDataException.class);
    }

    @Test
    void conflictOnUsernameOrEmailIsAlreadyExists() {
        when(userResource.toRepresentation()).thenReturn(account("1002003004", "Ana", "Ruiz", "ana@example.com"));
        doThrow(failure(409, null)).when(userResource).update(any());

        assertThatThrownBy(() -> client.updateUser(USER_ID, "1002003004", "Ana", "Ruiz", "otra@example.com"))
                .isInstanceOf(UserAlreadyExistsException.class);
    }

    @Test
    void anAccountThatNoLongerExistsIsReportedClearly() {
        WebApplicationException notFound = failure(404, null);
        when(userResource.toRepresentation()).thenThrow(notFound);

        assertThatThrownBy(() -> client.updateUser(USER_ID, "1002003004", "Ana", "Ruiz", "ana@example.com"))
                .isInstanceOf(IdentityProviderException.class)
                .hasMessageContaining("no existe");

        verify(userResource, never()).update(any());
    }

    @Test
    void unexpectedProviderErrorsAreBadGateway() {
        when(userResource.toRepresentation()).thenReturn(account("1002003004", "Ana", "Ruiz", "ana@example.com"));
        doThrow(failure(500, null)).when(userResource).update(any());

        assertThatThrownBy(() -> client.updateUser(USER_ID, "1002003004", "Anabel", "Ruiz", "ana@example.com"))
                .isInstanceOf(IdentityProviderException.class);
    }
}
