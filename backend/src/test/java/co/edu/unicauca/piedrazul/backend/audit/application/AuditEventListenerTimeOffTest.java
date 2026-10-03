package co.edu.unicauca.piedrazul.backend.audit.application;

import co.edu.unicauca.piedrazul.backend.audit.domain.AuditEvent;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditEventRepository;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditOutcome;
import co.edu.unicauca.piedrazul.backend.doctors.events.TimeOffChangedEvent;
import co.edu.unicauca.piedrazul.backend.shared.enums.AuditAction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AuditEventListenerTimeOffTest {

    private final AuditEventRepository repository = mock(AuditEventRepository.class);
    private final AuditEventListener listener = new AuditEventListener(repository);

    @ParameterizedTest
    @CsvSource({
            "CREATED,DESCANSO_CREADO",
            "DELETED,DESCANSO_ELIMINADO",
            "TRUNCATED,DESCANSO_RECORTADO"
    })
    void eachChangeIsRecordedWithItsAction(TimeOffChangedEvent.Change change, AuditAction expected) {
        listener.on(new TimeOffChangedEvent("doctor-1", change, "admin-1", "[ADMIN]", "corr-1",
                "{\"startDate\":\"2026-12-01\"}", "{\"startDate\":\"2026-12-01\"}"));

        ArgumentCaptor<AuditEvent> saved = ArgumentCaptor.forClass(AuditEvent.class);
        verify(repository).save(saved.capture());
        AuditEvent event = saved.getValue();
        assertThat(event.getAction()).isEqualTo(expected);
        assertThat(event.getActorId()).isEqualTo("admin-1");
        assertThat(event.getActorRole()).isEqualTo("[ADMIN]");
        assertThat(event.getTargetEntityType()).isEqualTo("Doctor");
        assertThat(event.getTargetEntityId()).isEqualTo("doctor-1");
        assertThat(event.getOutcome()).isEqualTo(AuditOutcome.EXITOSO);
        assertThat(event.getCorrelationId()).isEqualTo("corr-1");
    }

    @Test
    void creationHasNoBeforeState() {
        listener.on(new TimeOffChangedEvent("doctor-1", TimeOffChangedEvent.Change.CREATED, "admin-1", "[ADMIN]",
                "corr-1", null, "{\"startDate\":\"2026-12-01\"}"));

        ArgumentCaptor<AuditEvent> saved = ArgumentCaptor.forClass(AuditEvent.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getBeforeState()).isNull();
        assertThat(saved.getValue().getAfterState()).isNotNull();
    }
}
