package co.edu.unicauca.piedrazul.backend.audit.application;

import co.edu.unicauca.piedrazul.backend.audit.domain.AuditEvent;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditEventRepository;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditOutcome;
import co.edu.unicauca.piedrazul.backend.patients.events.PatientUpdatedEvent;
import co.edu.unicauca.piedrazul.backend.shared.enums.AuditAction;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AuditEventListenerPatientTest {

    @Test
    void patientUpdateIsRecordedWithActorTargetAndStates() {
        AuditEventRepository repository = mock(AuditEventRepository.class);
        AuditEventListener listener = new AuditEventListener(repository);

        listener.on(new PatientUpdatedEvent(
                "patient-1", "doctor-9", "[DOCTOR]", "corr-1",
                "{\"phone\":\"[modificado]\"}", "{\"phone\":\"[modificado]\"}"));

        ArgumentCaptor<AuditEvent> saved = ArgumentCaptor.forClass(AuditEvent.class);
        verify(repository).save(saved.capture());
        AuditEvent event = saved.getValue();
        assertThat(event.getAction()).isEqualTo(AuditAction.PACIENTE_MODIFICADO);
        assertThat(event.getActorId()).isEqualTo("doctor-9");
        assertThat(event.getActorRole()).isEqualTo("[DOCTOR]");
        assertThat(event.getTargetEntityType()).isEqualTo("Paciente");
        assertThat(event.getTargetEntityId()).isEqualTo("patient-1");
        assertThat(event.getOutcome()).isEqualTo(AuditOutcome.EXITOSO);
        assertThat(event.getCorrelationId()).isEqualTo("corr-1");
        assertThat(event.getBeforeState()).isEqualTo("{\"phone\":\"[modificado]\"}");
        assertThat(event.getAfterState()).isEqualTo("{\"phone\":\"[modificado]\"}");
    }
}
