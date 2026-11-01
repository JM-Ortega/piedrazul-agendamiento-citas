package co.edu.unicauca.piedrazul.backend.audit.application;

import co.edu.unicauca.piedrazul.backend.audit.domain.AuditEvent;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditEventRepository;
import co.edu.unicauca.piedrazul.backend.shared.audit.CorrelationIdFilter;
import co.edu.unicauca.piedrazul.backend.shared.enums.AuditAction;
import co.edu.unicauca.piedrazul.backend.user.events.LoginAttemptedEvent;
import co.edu.unicauca.piedrazul.backend.user.events.UserCreatedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;

/**
 * Un cliente no puede ocupar el id con el que se deduplica un inicio de sesión. Si lograra guardar
 * un registro con {@code keycloak-session:<id>}, el inicio de sesión real de esa sesión se tomaría
 * por repetido y nunca quedaría en la auditoría.
 */
@ExtendWith(MockitoExtension.class)
class CorrelationAuditCollisionTest {

    private static final String SESSION_ID = "7c9e6679-7425-40de-944b-e07fc1f90ae7";
    private static final String USER_ID = "11111111-1111-1111-1111-111111111111";
    private static final String INTERNAL_KEY = "keycloak-session:" + SESSION_ID;

    @Mock
    private AuditEventRepository repository;

    private final List<AuditEvent> saved = new ArrayList<>();

    private AuditEventListener listener;

    @BeforeEach
    void setUp() {
        lenient().doAnswer(call -> saved.add(call.getArgument(0))).when(repository).save(any());
        lenient().when(repository.existsByCorrelationId(anyString())).thenAnswer(call -> saved.stream()
                .anyMatch(event -> call.getArgument(0).equals(event.getCorrelationId())));
        listener = new AuditEventListener(repository);
    }

    private static LoginAttemptedEvent login() {
        return new LoginAttemptedEvent(USER_ID, "[DOCTOR]", true, "evento-1", SESSION_ID,
                Instant.parse("2026-10-03T14:58:12Z"), null);
    }

    /** Una operación auditada dentro de una petición, que toma el id del MDC como hacen los servicios. */
    private void auditedRequestWith(String correlationHeader) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Correlation-Id", correlationHeader);

        new CorrelationIdFilter().doFilter(request, new MockHttpServletResponse(), (req, res) ->
                listener.on(UserCreatedEvent.of("usuario", "actor", "[ADMIN]", MDC.get("correlationId"))));
    }

    private long loginsSaved() {
        return saved.stream().filter(event -> event.getAction() == AuditAction.LOGIN_EXITOSO).count();
    }

    @Test
    void theCollisionExistsIfAnInternalIdReachesTheAudit() {
        // Control: sin la frontera del filtro, un registro con la clave interna oculta el login.
        listener.on(UserCreatedEvent.of("usuario", "actor", "[ADMIN]", INTERNAL_KEY));

        listener.on(login());

        assertThat(loginsSaved()).isZero();
    }

    @Test
    void aClientCannotOccupyTheKeyOfASession() throws Exception {
        auditedRequestWith(INTERNAL_KEY);

        listener.on(login());

        assertThat(loginsSaved()).isEqualTo(1);
        assertThat(saved).noneMatch(event -> event.getAction() == AuditAction.USUARIO_CREADO
                && INTERNAL_KEY.equals(event.getCorrelationId()));
    }

    @Test
    void aClientCannotOccupyTheKeyOfAFailedLogin() throws Exception {
        String internalKey = "keycloak-event:evento-2";
        LoginAttemptedEvent failure = new LoginAttemptedEvent(USER_ID, "[DOCTOR]", false, "evento-2", null,
                Instant.parse("2026-10-03T14:58:12Z"), "invalid_user_credentials");

        auditedRequestWith(internalKey);

        listener.on(failure);

        assertThat(saved).filteredOn(event -> event.getAction() == AuditAction.LOGIN_FALLIDO).hasSize(1);
    }

    @Test
    void aCanonicalIdEqualToTheSessionDoesNotCollideEither() throws Exception {
        // El id de sesión es un UUID, pero la clave interna siempre lleva su prefijo.
        auditedRequestWith(SESSION_ID);

        listener.on(login());

        assertThat(loginsSaved()).isEqualTo(1);
    }

    @Test
    void aSessionReadTwiceIsStillSavedOnce() throws Exception {
        auditedRequestWith(INTERNAL_KEY);

        listener.on(login());
        listener.on(login());

        assertThat(loginsSaved()).isEqualTo(1);
        assertThat(saved).filteredOn(event -> event.getAction() == AuditAction.LOGIN_EXITOSO)
                .extracting(AuditEvent::getCorrelationId)
                .containsExactly(INTERNAL_KEY);
    }
}
