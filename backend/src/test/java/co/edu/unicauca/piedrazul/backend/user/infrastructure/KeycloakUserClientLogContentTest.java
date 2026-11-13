package co.edu.unicauca.piedrazul.backend.user.infrastructure;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import co.edu.unicauca.piedrazul.backend.config.security.KeycloakProperties;
import co.edu.unicauca.piedrazul.backend.shared.audit.SecurityContextExtractor;
import co.edu.unicauca.piedrazul.backend.user.exception.InvalidUserDataException;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Qué deja en el log un rechazo de Keycloak: el diagnóstico (campo y clave del error) sí, los
 * valores enviados que Keycloak repite en {@code params} no.
 */
@ExtendWith(MockitoExtension.class)
class KeycloakUserClientLogContentTest {

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

    private final Logger clientLogger = (Logger) LoggerFactory.getLogger(KeycloakUserClient.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private KeycloakUserClient client;

    @BeforeEach
    void setUp() {
        appender.start();
        clientLogger.addAppender(appender);
        client = new KeycloakUserClient(keycloak, props, securityExtractor, eventPublisher);
        when(props.getRealm()).thenReturn("piedrazul");
        when(keycloak.realm("piedrazul")).thenReturn(realmResource);
        when(realmResource.users()).thenReturn(usersResource);
        when(usersResource.get(USER_ID.toString())).thenReturn(userResource);
        UserRepresentation current = new UserRepresentation();
        current.setUsername("1002003004");
        when(userResource.toRepresentation()).thenReturn(current);
    }

    @AfterEach
    void tearDown() {
        clientLogger.detachAppender(appender);
    }

    private void rejectUpdateWith(int status, String body) {
        Response response = mock(Response.class);
        when(response.getStatus()).thenReturn(status);
        when(response.hasEntity()).thenReturn(true);
        when(response.readEntity(String.class)).thenReturn(body);
        doThrow(new WebApplicationException("keycloak " + status, response)).when(userResource).update(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void aRejectionKeepsTheFieldAndErrorKeyButNotTheValuesKeycloakEchoes() {
        rejectUpdateWith(400,
                "{\"field\":\"email\",\"errorMessage\":\"error-invalid-email\","
                        + "\"params\":[\"SYN-CORREO@example.test\",\"SYN-USUARIO\"]}");

        assertThatThrownBy(() -> client.updateUser(USER_ID, "1002003004", "Ana", "Ruiz", "SYN-CORREO@example.test"))
                .isInstanceOf(InvalidUserDataException.class);

        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage())
                    .contains("field=email")
                    .contains("errorMessage=error-invalid-email")
                    .doesNotContain("SYN-CORREO")
                    .doesNotContain("SYN-USUARIO");
        });
    }

    @Test
    void aBodyThatIsNotJsonOnlyLeavesItsSize() {
        rejectUpdateWith(400, "<html>SYN-CORREO@example.test</html>");

        assertThatThrownBy(() -> client.updateUser(USER_ID, "1002003004", "Ana", "Ruiz", "x@example.test"))
                .isInstanceOf(InvalidUserDataException.class);

        assertThat(appender.list).singleElement().satisfies(event ->
                assertThat(event.getFormattedMessage()).contains("no interpretable").doesNotContain("SYN-CORREO"));
    }
}
