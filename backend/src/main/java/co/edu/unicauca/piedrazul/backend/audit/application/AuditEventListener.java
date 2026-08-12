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

//    @ApplicationModuleListener
//    void on(CitaReagendadaEvent event) {
//        repository.save(AuditEvent.builder()
//                .actor(event.username(), event.rol())
//                .action(AuditAction.CITA_REAGENDADA)
//                .target("Cita", event.citaId().toString())
//                .outcome(AuditOutcome.SUCCESS)
//                .states(event.estadoAnteriorJson(), event.estadoNuevoJson())
//                .correlationId(event.correlationId())
//                .build());
//    }
//
//    @ApplicationModuleListener
//    void on(UsuarioCreadoEvent event) {
//        repository.save(AuditEvent.builder()
//                .actor(event.creadoPor(), event.rolCreador())
//                .action(AuditAction.USUARIO_CREADO)
//                .target("Usuario", event.usuarioId().toString())
//                .outcome(AuditOutcome.SUCCESS)
//                .build());
//    }
//
//    @ApplicationModuleListener
//    void on(HistoriaClinicaConsultadaEvent event) {
//        repository.save(AuditEvent.builder()
//                .actor(event.username(), event.rol())
//                .action(AuditAction.HISTORIA_CLINICA_CONSULTADA)
//                .target("HistoriaClinica", event.pacienteId().toString())
//                .outcome(AuditOutcome.SUCCESS)
//                .build());
//    }
}



/*
import co.edu.unicauca.piedrazul.backend.shared.events.audit.AppointmentCreatedEvent;
import co.edu.unicauca.piedrazul.backend.shared.enums.AuditAction;
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

 */
