package co.edu.unicauca.piedrazul.backend.audit.domain;

import co.edu.unicauca.piedrazul.backend.shared.enums.AuditAction;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditEventTest {

    @Test
    void buildShouldCreateValidAuditEventWithStates() {
        AuditEvent auditEvent = AuditEvent.builder()
                .actor("user-123", "DOCTOR")
                .action(AuditAction.USUARIO_CREADO)
                .target("Usuario", "user-123")
                .outcome(AuditOutcome.EXITOSO)
                .correlationId("corr-001")
                .states("{\"before\":\"doc\"}", "{\"after\":\"doc,paciente\"}")
                .build();

        assertThat(auditEvent.getId()).isNotNull();
        assertThat(auditEvent.getTimestamp()).isNotNull();
        assertThat(auditEvent.getActorId()).isEqualTo("user-123");
        assertThat(auditEvent.getActorRole()).isEqualTo("DOCTOR");
        assertThat(auditEvent.getAction()).isEqualTo(AuditAction.USUARIO_CREADO);
        assertThat(auditEvent.getTargetEntityType()).isEqualTo("Usuario");
        assertThat(auditEvent.getTargetEntityId()).isEqualTo("user-123");
        assertThat(auditEvent.getOutcome()).isEqualTo(AuditOutcome.EXITOSO);
        assertThat(auditEvent.getCorrelationId()).isEqualTo("corr-001");
        assertThat(auditEvent.getBeforeState()).isEqualTo("{\"before\":\"doc\"}");
        assertThat(auditEvent.getAfterState()).isEqualTo("{\"after\":\"doc,paciente\"}");
    }

    @Test
    void buildShouldRejectMissingAction() {
        assertThatThrownBy(() -> AuditEvent.builder()
                .actor("user-123", "DOCTOR")
                .target("Usuario", "user-123")
                .outcome(AuditOutcome.EXITOSO)
                .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Action es obligatorio");
    }

    @Test
    void buildShouldRejectMissingActor() {
        assertThatThrownBy(() -> AuditEvent.builder()
                .action(AuditAction.USUARIO_CREADO)
                .target("Usuario", "user-123")
                .outcome(AuditOutcome.EXITOSO)
                .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Actor es obligatorio");
    }

    @Test
    void buildShouldRejectMissingOutcome() {
        assertThatThrownBy(() -> AuditEvent.builder()
                .actor("user-123", "DOCTOR")
                .action(AuditAction.USUARIO_CREADO)
                .target("Usuario", "user-123")
                .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Outcome es obligatorio");
    }
}
