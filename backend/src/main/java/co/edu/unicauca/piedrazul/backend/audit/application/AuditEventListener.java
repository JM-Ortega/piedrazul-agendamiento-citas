package co.edu.unicauca.piedrazul.backend.audit.application;

import co.edu.unicauca.piedrazul.backend.appointment.events.ScheduledAppointmentEvent;
import co.edu.unicauca.piedrazul.backend.doctors.events.TimeOffChangedEvent;
import co.edu.unicauca.piedrazul.backend.medicalCheckup.events.MedicalCheckupCreatedEvent;
import co.edu.unicauca.piedrazul.backend.patients.events.PatientUpdatedEvent;
import co.edu.unicauca.piedrazul.backend.shared.audit.AuditTargetType;
import co.edu.unicauca.piedrazul.backend.shared.enums.AuditAction;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditEvent;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditEventRepository;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditOutcome;
import co.edu.unicauca.piedrazul.backend.user.events.LoginAttemptedEvent;
import co.edu.unicauca.piedrazul.backend.user.events.UserAccountStatusChangedEvent;
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
                .target(AuditTargetType.CITA, event.citaId().toString())
                .outcome(AuditOutcome.EXITOSO)
                .correlationId(event.correlationId())
                .build());
    }

    @ApplicationModuleListener
    void on(MedicalCheckupCreatedEvent event) {
        repository.save(AuditEvent.builder()
                .actor(event.username(), event.rol())
                .action(AuditAction.CONTROL_MEDICO_CREADO)
                .target(AuditTargetType.CONTROL_MEDICO, event.medicalCheckupId().toString())
                .outcome(AuditOutcome.EXITOSO)
                .correlationId(event.correlationId())
                .build());
    }

    @ApplicationModuleListener
    void on(TimeOffChangedEvent event) {
        AuditAction action = switch (event.change()) {
            case CREATED -> AuditAction.DESCANSO_CREADO;
            case DELETED -> AuditAction.DESCANSO_ELIMINADO;
            case TRUNCATED -> AuditAction.DESCANSO_RECORTADO;
        };

        repository.save(AuditEvent.builder()
                .actor(event.performedBy(), event.performedByRole())
                .action(action)
                .target(AuditTargetType.DOCTOR, event.doctorId())
                .outcome(AuditOutcome.EXITOSO)
                .correlationId(event.correlationId())
                .states(event.beforeState(), event.afterState())
                .build());
    }

    @ApplicationModuleListener
    void on(PatientUpdatedEvent event) {
        repository.save(AuditEvent.builder()
                .actor(event.performedBy(), event.performedByRole())
                .action(AuditAction.PACIENTE_MODIFICADO)
                .target(AuditTargetType.PACIENTE, event.patientId())
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
                .target(AuditTargetType.USUARIO, event.userId())
                .outcome(AuditOutcome.EXITOSO)
                .correlationId(event.correlationId())
                .build());
    }

    @ApplicationModuleListener
    void on(UserAccountStatusChangedEvent event) {
        AuditAction action = event.enabledAfter()
                ? AuditAction.USUARIO_ACTIVADO
                : AuditAction.USUARIO_DESACTIVADO;

        repository.save(AuditEvent.builder()
                .actor(event.performedBy(), event.performedByRole())
                .action(action)
                .target(AuditTargetType.PACIENTE, event.patientId())
                .outcome(AuditOutcome.EXITOSO)
                .correlationId(event.correlationId())
                .states(enabledJson(event.enabledBefore()), enabledJson(event.enabledAfter()))
                .build());
    }

    private static String enabledJson(boolean enabled) {
        return "{\"enabled\":" + enabled + "}";
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
                .target(AuditTargetType.USUARIO, event.userId())
                .outcome(AuditOutcome.EXITOSO)
                .correlationId(event.correlationId())
                .states(event.rolesBefore(), event.rolesAfter())
                .build());
    }

    /**
     * El id de correlación identifica el hecho en Keycloak: la sesión que abrió un inicio de sesión
     * exitoso o el evento de un intento fallido. Tras un reinicio, el backend vuelve a leer el
     * último día de eventos y así no se guardan dos veces.
     */
    @ApplicationModuleListener
    void on(LoginAttemptedEvent event) {
        String correlationId = event.successful()
                ? "keycloak-session:" + event.sessionId()
                : "keycloak-event:" + event.keycloakEventId();

        if (repository.existsByCorrelationId(correlationId)) {
            return;
        }

        repository.save(AuditEvent.builder()
                .occurredAt(event.occurredAt())
                .actor(event.userId(), event.roles())
                .action(event.successful() ? AuditAction.LOGIN_EXITOSO : AuditAction.LOGIN_FALLIDO)
                .target(AuditTargetType.USUARIO, event.userId())
                .outcome(event.successful() ? AuditOutcome.EXITOSO : AuditOutcome.FALLIDO)
                .correlationId(correlationId)
                .states(null, loginFailureJson(event.error()))
                .build());
    }

    /** El motivo que da Keycloak, solo si es un código esperado (minúsculas y guiones bajos). */
    private static String loginFailureJson(String error) {
        if (error == null || !error.matches("[a-z_]{1,60}")) {
            return null;
        }
        return "{\"motivo\":\"" + error + "\"}";
    }
}
