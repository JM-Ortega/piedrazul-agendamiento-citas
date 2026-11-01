package co.edu.unicauca.piedrazul.backend.audit.application;

import co.edu.unicauca.piedrazul.backend.audit.domain.AuditEvent;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditEventRepository;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditOutcome;
import co.edu.unicauca.piedrazul.backend.shared.audit.AuditTargetType;
import co.edu.unicauca.piedrazul.backend.shared.enums.AuditAction;
import co.edu.unicauca.piedrazul.backend.user.events.LoginAttemptedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Los intentos de inicio de sesión del personal se guardan una sola vez, con la hora de Keycloak. */
@ExtendWith(MockitoExtension.class)
class AuditEventListenerLoginTest {

    private static final String USER_ID = "11111111-1111-1111-1111-111111111111";
    private static final Instant OCCURRED_AT = Instant.parse("2026-10-03T14:58:12.345Z");

    @Mock
    private AuditEventRepository repository;

    @InjectMocks
    private AuditEventListener listener;

    @Captor
    private ArgumentCaptor<AuditEvent> auditCaptor;

    private static LoginAttemptedEvent success() {
        return new LoginAttemptedEvent(USER_ID, "[DOCTOR, SCHEDULER]", true, "e1", "s1", OCCURRED_AT, null);
    }

    private static LoginAttemptedEvent failure(String error) {
        return new LoginAttemptedEvent(USER_ID, "[DOCTOR]", false, "e2", null, OCCURRED_AT, error);
    }

    @Test
    void aSuccessfulLoginIsSavedAtTheTimeItHappenedInKeycloak() {
        listener.on(success());

        verify(repository).save(auditCaptor.capture());
        AuditEvent saved = auditCaptor.getValue();
        assertThat(saved.getAction()).isEqualTo(AuditAction.LOGIN_EXITOSO);
        assertThat(saved.getOutcome()).isEqualTo(AuditOutcome.EXITOSO);
        assertThat(saved.getActorId()).isEqualTo(USER_ID);
        assertThat(saved.getActorRole()).isEqualTo("[DOCTOR, SCHEDULER]");
        assertThat(saved.getTargetEntityType()).isEqualTo(AuditTargetType.USUARIO);
        assertThat(saved.getTargetEntityId()).isEqualTo(USER_ID);
        assertThat(saved.getTimestamp()).isEqualTo(OCCURRED_AT);
        assertThat(saved.getCorrelationId()).isEqualTo("keycloak-session:s1");
    }

    @Test
    void aFailedLoginIsSavedWithItsReason() {
        listener.on(failure("invalid_user_credentials"));

        verify(repository).save(auditCaptor.capture());
        AuditEvent saved = auditCaptor.getValue();
        assertThat(saved.getAction()).isEqualTo(AuditAction.LOGIN_FALLIDO);
        assertThat(saved.getOutcome()).isEqualTo(AuditOutcome.FALLIDO);
        assertThat(saved.getCorrelationId()).isEqualTo("keycloak-event:e2");
        assertThat(saved.getAfterState()).isEqualTo("{\"motivo\":\"invalid_user_credentials\"}");
    }

    @Test
    void anUnexpectedReasonIsNotWrittenIntoTheJson() {
        listener.on(failure("raro\",\"x\":\"y"));

        verify(repository).save(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getAfterState()).isNull();
    }

    @Test
    void aSessionAlreadyRecordedIsNotSavedAgain() {
        // Tras un reinicio, el backend relee el último día de eventos de Keycloak.
        when(repository.existsByCorrelationId("keycloak-session:s1")).thenReturn(true);

        listener.on(success());

        verify(repository, never()).save(any());
    }

    @Test
    void aFailureAlreadyRecordedIsNotSavedAgain() {
        when(repository.existsByCorrelationId("keycloak-event:e2")).thenReturn(true);

        listener.on(failure("invalid_user_credentials"));

        verify(repository, never()).save(any());
    }
}
