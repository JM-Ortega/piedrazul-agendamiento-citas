package co.edu.unicauca.piedrazul.backend.audit.application;

import co.edu.unicauca.piedrazul.backend.shared.events.audit.AppointmentCreatedEvent;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditAction;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditModule;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class AuditEventListener {

    private final AuditLogService auditLogService;

    public AuditEventListener(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @EventListener
    public void onAppointmentCreated(AppointmentCreatedEvent event) {
        auditLogService.register(
                AuditAction.APPOINTMENT_CREATED,
                AuditModule.APPOINTMENT,
                event.appointmentId(),
                event.performedBy()
        );
    }
}
