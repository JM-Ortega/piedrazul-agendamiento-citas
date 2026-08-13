package co.edu.unicauca.piedrazul.backend.audit.application;

import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output.CitaAgendadaEvent;
import co.edu.unicauca.piedrazul.backend.shared.enums.AuditAction;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditEvent;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditEventRepository;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditOutcome;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
public class AuditEventListener {

    private final AuditEventRepository repository;

    public AuditEventListener(AuditEventRepository repository) {
        this.repository = repository;
    }

    @ApplicationModuleListener
    void on(CitaAgendadaEvent event) {
        repository.save(AuditEvent.builder()
                .actor(event.username(), event.rol())
                .action(AuditAction.CITA_AGENDADA)
                .target("Cita", event.citaId().toString())
                .outcome(AuditOutcome.EXITOSO)
                .correlationId(event.correlationId())
                .build());
    }
}