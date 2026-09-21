package co.edu.unicauca.piedrazul.backend.audit.application;

import co.edu.unicauca.piedrazul.backend.appointment.events.ScheduledAppointmentEvent;
import co.edu.unicauca.piedrazul.backend.clinicalHistory.events.ClinicalHistoryCreatedEvent;
import co.edu.unicauca.piedrazul.backend.patients.events.PatientUpdatedEvent;
import co.edu.unicauca.piedrazul.backend.shared.enums.AuditAction;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditEvent;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditEventRepository;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditOutcome;
import co.edu.unicauca.piedrazul.backend.user.events.UserCreatedEvent;
import co.edu.unicauca.piedrazul.backend.user.events.UserRoleAssignedEvent;
import co.edu.unicauca.piedrazul.backend.user.events.UserRoleRevokedEvent;
import co.edu.unicauca.piedrazul.backend.user.events.UserRoleAuditEvent;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
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

    @ApplicationModuleListener
    void on(ClinicalHistoryCreatedEvent event) {
        repository.save(AuditEvent.builder()
                .actor(event.username(), event.rol())
                .action(AuditAction.HISTORIA_CLINICA_CREADA)
                .target("HistoriaClinica", event.clinicalHistoryId().toString())
                .outcome(AuditOutcome.EXITOSO)
                .correlationId(event.correlationId())
                .build());
    }

    @ApplicationModuleListener
    void on(PatientUpdatedEvent event) {
        repository.save(AuditEvent.builder()
                .actor(event.performedBy(), event.performedByRole())
                .action(AuditAction.PACIENTE_MODIFICADO)
                .target("Paciente", event.patientId())
                .outcome(AuditOutcome.EXITOSO)
                .correlationId(event.correlationId())
                .states(event.beforeState(), event.afterState())
                .build());
    }

    // Es diferente porque depende de keycloack, esta anotación asegura que la
    // auditoría solo se guarde si la operación
    // principal ya fue confirmada correctamente
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void on(UserCreatedEvent event) {
        repository.save(AuditEvent.builder()
                .actor(event.createdBy(), event.creatorRole())
                .action(AuditAction.USUARIO_CREADO)
                .target("Usuario", event.userId())
                .outcome(AuditOutcome.EXITOSO)
                .correlationId(event.correlationId())
                .build());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void on(UserRoleAuditEvent event) {
        AuditAction action = switch (event) {
            case UserRoleAssignedEvent e -> AuditAction.ROL_ASIGNADO;
            case UserRoleRevokedEvent e -> AuditAction.ROL_REVOCADO;
        };

        repository.save(AuditEvent.builder()
                .actor(event.performedBy(), event.performedByRole())
                .action(action)
                .target("Usuario", event.userId())
                .outcome(AuditOutcome.EXITOSO)
                .correlationId(event.correlationId())
                .states(event.rolesBefore(), event.rolesAfter())
                .build());
    }
}