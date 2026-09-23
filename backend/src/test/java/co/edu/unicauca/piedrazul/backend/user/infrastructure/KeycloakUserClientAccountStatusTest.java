package co.edu.unicauca.piedrazul.backend.user.infrastructure;

import co.edu.unicauca.piedrazul.backend.config.security.KeycloakProperties;
import co.edu.unicauca.piedrazul.backend.shared.audit.SecurityContextExtractor;
import co.edu.unicauca.piedrazul.backend.user.events.UserAccountStatusChangedEvent;
import co.edu.unicauca.piedrazul.backend.user.exception.IdentityProviderException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterEach;
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
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KeycloakUserClientAccountStatusTest {

    private static final UUID USER_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID PATIENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

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
        lenient().when(securityExtractor.currentActorId()).thenReturn("admin-1");
        lenient().when(securityExtractor.currentActorRoles()).thenReturn("[ADMIN]");
        MDC.put("correlationId", "corr-1");
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    private static UserRepresentation account(Boolean enabled) {
        UserRepresentation user = new UserRepresentation();
        user.setUsername("1002003004");
        user.setEnabled(enabled);
        return user;
    }

    private static WebApplicationException failure(int status) {
        Response response = mock(Response.class);
        when(response.getStatus()).thenReturn(status);
        return new WebApplicationException("keycloak " + status, response);
    }

    private UserAccountStatusChangedEvent publishedEvent() {
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(event.capture());
        return (UserAccountStatusChangedEvent) event.getValue();
    }

    @Test
    void activatingADisabledAccountEnablesItAndPublishesTheAuditEvent() {
        when(userResource.toRepresentation()).thenReturn(account(false));

        client.setEnabled(USER_ID, true, PATIENT_ID);

        ArgumentCaptor<UserRepresentation> sent = ArgumentCaptor.forClass(UserRepresentation.class);
        verify(userResource).update(sent.capture());
        assertThat(sent.getValue().isEnabled()).isTrue();

        UserAccountStatusChangedEvent event = publishedEvent();
        // La auditoría registra al paciente, no el id de su cuenta de Keycloak.
        assertThat(event.patientId()).isEqualTo(PATIENT_ID.toString());
        assertThat(event.performedBy()).isEqualTo("admin-1");
        assertThat(event.performedByRole()).isEqualTo("[ADMIN]");
        assertThat(event.correlationId()).isEqualTo("corr-1");
        assertThat(event.enabledBefore()).isFalse();
        assertThat(event.enabledAfter()).isTrue();
    }

    @Test
    void deactivatingAnEnabledAccountDisablesItAndPublishesTheAuditEvent() {
        when(userResource.toRepresentation()).thenReturn(account(true));

        client.setEnabled(USER_ID, false, PATIENT_ID);

        ArgumentCaptor<UserRepresentation> sent = ArgumentCaptor.forClass(UserRepresentation.class);
        verify(userResource).update(sent.capture());
        assertThat(sent.getValue().isEnabled()).isFalse();

        UserAccountStatusChangedEvent event = publishedEvent();
        assertThat(event.enabledBefore()).isTrue();
        assertThat(event.enabledAfter()).isFalse();
    }

    @Test
    void theOtherDataOfTheAccountIsSentBackUntouched() {
        UserRepresentation stored = account(true);
        stored.setFirstName("Ana");
        stored.setEmail("ana@example.com");
        when(userResource.toRepresentation()).thenReturn(stored);

        client.setEnabled(USER_ID, false, PATIENT_ID);

        ArgumentCaptor<UserRepresentation> sent = ArgumentCaptor.forClass(UserRepresentation.class);
        verify(userResource).update(sent.capture());
        assertThat(sent.getValue().getUsername()).isEqualTo("1002003004");
        assertThat(sent.getValue().getFirstName()).isEqualTo("Ana");
        assertThat(sent.getValue().getEmail()).isEqualTo("ana@example.com");
    }

    @Test
    void anAccountAlreadyInTheRequestedStateIsLeftAloneAndNothingIsAudited() {
        when(userResource.toRepresentation()).thenReturn(account(true));
        client.setEnabled(USER_ID, true, PATIENT_ID);

        when(userResource.toRepresentation()).thenReturn(account(false));
        client.setEnabled(USER_ID, false, PATIENT_ID);

        verify(userResource, never()).update(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void anAccountWithoutTheEnabledFlagCountsAsDisabled() {
        when(userResource.toRepresentation()).thenReturn(account(null));

        client.setEnabled(USER_ID, true, PATIENT_ID);

        verify(userResource).update(any());
        assertThat(publishedEvent().enabledBefore()).isFalse();
    }

    @Test
    void anAccountThatDoesNotExistIsReportedAndNothingIsChanged() {
        WebApplicationException notFound = failure(404);
        when(userResource.toRepresentation()).thenThrow(notFound);

        assertThatThrownBy(() -> client.setEnabled(USER_ID, false, PATIENT_ID))
                .isInstanceOf(IdentityProviderException.class)
                .hasMessageContaining("no existe");

        verify(userResource, never()).update(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void ifTheProviderRejectsTheChangeNoEventIsPublished() {
        when(userResource.toRepresentation()).thenReturn(account(true));
        doThrow(failure(500)).when(userResource).update(any());

        assertThatThrownBy(() -> client.setEnabled(USER_ID, false, PATIENT_ID))
                .isInstanceOf(IdentityProviderException.class);

        verifyNoInteractions(eventPublisher);
    }
}
