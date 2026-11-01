package co.edu.unicauca.piedrazul.backend.user.infrastructure;

import co.edu.unicauca.piedrazul.backend.config.security.KeycloakProperties;
import co.edu.unicauca.piedrazul.backend.shared.audit.SecurityContextExtractor;
import co.edu.unicauca.piedrazul.backend.user.events.UserCreatedEvent;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.UsersResource;
import org.keycloak.representations.idm.UserRepresentation;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Al crear una cuenta, la auditoría necesita saber quién actúa. Cuando no hay usuario autenticado
 * (el registro público de un paciente) actúa la propia cuenta recién creada, cuyo id solo se conoce
 * después de crearla: por eso el cliente debe preguntárselo al extractor con ese id.
 */
@ExtendWith(MockitoExtension.class)
class KeycloakUserClientActorTest {

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

    @Test
    void theCreationEventAsksTheExtractorForTheActorOfTheNewAccount() {
        UUID created = UUID.randomUUID();
        when(props.getRealm()).thenReturn("piedrazul");
        when(keycloak.realm("piedrazul")).thenReturn(realmResource);
        when(realmResource.users()).thenReturn(usersResource);
        when(usersResource.create(any(UserRepresentation.class))).thenReturn(Response.status(Response.Status.CREATED)
                .header("Location", "http://kc/admin/realms/piedrazul/users/" + created).build());
        when(securityExtractor.currentActorId(created.toString())).thenReturn(created.toString());
        when(securityExtractor.currentActorRoles(created.toString())).thenReturn("N/A");

        new KeycloakUserClient(keycloak, props, securityExtractor, eventPublisher)
                .createUser("55000001", "Pedro", "Ramírez", "pedro@example.com", "Secreta123");

        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(published.capture());
        UserCreatedEvent event = (UserCreatedEvent) published.getValue();
        assertThat(event.userId()).isEqualTo(created.toString());
        assertThat(event.createdBy()).isEqualTo(created.toString());
        assertThat(event.creatorRole()).isEqualTo("N/A");
    }
}
