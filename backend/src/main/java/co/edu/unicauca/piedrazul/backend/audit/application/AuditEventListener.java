package co.edu.unicauca.piedrazul.backend.audit.application;

import co.edu.unicauca.piedrazul.backend.appointment.events.ScheduledAppointmentEvent;
import co.edu.unicauca.piedrazul.backend.shared.enums.AuditAction;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditEvent;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditEventRepository;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditOutcome;
import co.edu.unicauca.piedrazul.backend.user.events.UserCreatedEvent;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class AuditEventListener {

    private final AuditEventRepository repository;

    public AuditEventListener(AuditEventRepository repository) {
        this.repository = repository;
    }

    @ApplicationModuleListener
    void on(ScheduledAppointmentEvent event) {
        repository.save(AuditEvent.builder()
                .actor(event.username(), event.rol())
                .action(AuditAction.CITA_AGENDADA)
                .target("Cita", event.citaId().toString())
                .outcome(AuditOutcome.EXITOSO)
                .correlationId(event.correlationId())
                .build());
    }

    // Es diferente porque depende de keycloack, esta anotación asegura que la auditoría solo se guarde si la operación
    // principal ya fue confirmada correctamente
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void on(UserCreatedEvent event) {
        repository.save(AuditEvent.builder()
                .actor(event.createdBy(), event.creatorRole())
                .action(AuditAction.USUARIO_CREADO)
                .target("Usuario", event.userId())
                .outcome(AuditOutcome.EXITOSO)
                .correlationId(event.correlationId())
                .build());
    }
}