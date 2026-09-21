package co.edu.unicauca.piedrazul.backend.audit.application;

import co.edu.unicauca.piedrazul.backend.appointment.events.ScheduledAppointmentEvent;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditEvent;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditEventRepository;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditOutcome;
import co.edu.unicauca.piedrazul.backend.clinicalHistory.events.ClinicalHistoryCreatedEvent;
import co.edu.unicauca.piedrazul.backend.shared.enums.AuditAction;
import co.edu.unicauca.piedrazul.backend.user.events.UserAccountStatusChangedEvent;
import co.edu.unicauca.piedrazul.backend.user.events.UserCreatedEvent;
import co.edu.unicauca.piedrazul.backend.user.events.UserRoleAssignedEvent;
import co.edu.unicauca.piedrazul.backend.user.events.UserRoleRevokedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AuditEventListenerTest {

    @Mock
    private AuditEventRepository repository;

    @InjectMocks
    private AuditEventListener listener;

    @Captor
    private ArgumentCaptor<AuditEvent> auditCaptor;

    @Test
    void onScheduledAppointmentEventShouldPersistAppointmentAudit() {
        ScheduledAppointmentEvent event = new ScheduledAppointmentEvent(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "doctor-01",
                "DOCTOR",
                "corr-appointment");

        listener.on(event);

        verify(repository).save(auditCaptor.capture());
        AuditEvent saved = auditCaptor.getValue();

        assertThat(saved.getActorId()).isEqualTo("doctor-01");
        assertThat(saved.getActorRole()).isEqualTo("DOCTOR");
        assertThat(saved.getAction()).isEqualTo(AuditAction.CITA_AGENDADA);
        assertThat(saved.getTargetEntityType()).isEqualTo("Cita");
        assertThat(saved.getTargetEntityId()).isEqualTo("11111111-1111-1111-1111-111111111111");
        assertThat(saved.getOutcome()).isEqualTo(AuditOutcome.EXITOSO);
        assertThat(saved.getCorrelationId()).isEqualTo("corr-appointment");
    }

    @Test
    void onClinicalHistoryCreatedEventShouldPersistClinicalHistoryAudit() {
        ClinicalHistoryCreatedEvent event = new ClinicalHistoryCreatedEvent(
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                "medico-02",
                "DOCTOR",
                "corr-clinical-history");

        listener.on(event);

        verify(repository).save(auditCaptor.capture());
        AuditEvent saved = auditCaptor.getValue();

        assertThat(saved.getAction()).isEqualTo(AuditAction.HISTORIA_CLINICA_CREADA);
        assertThat(saved.getTargetEntityType()).isEqualTo("HistoriaClinica");
        assertThat(saved.getTargetEntityId()).isEqualTo("22222222-2222-2222-2222-222222222222");
        assertThat(saved.getCorrelationId()).isEqualTo("corr-clinical-history");
    }

    @Test
    void onUserCreatedEventShouldPersistUserCreationAudit() {
        UserCreatedEvent event = UserCreatedEvent.of(
                "user-77",
                "admin-01",
                "ADMIN",
                "corr-user-created");

        listener.on(event);

        verify(repository).save(auditCaptor.capture());
        AuditEvent saved = auditCaptor.getValue();

        assertThat(saved.getAction()).isEqualTo(AuditAction.USUARIO_CREADO);
        assertThat(saved.getActorId()).isEqualTo("admin-01");
        assertThat(saved.getActorRole()).isEqualTo("ADMIN");
        assertThat(saved.getTargetEntityType()).isEqualTo("Usuario");
        assertThat(saved.getTargetEntityId()).isEqualTo("user-77");
        assertThat(saved.getOutcome()).isEqualTo(AuditOutcome.EXITOSO);
        assertThat(saved.getCorrelationId()).isEqualTo("corr-user-created");
    }

    @Test
    void onUserRoleAuditEventShouldPersistRoleAssignmentAndRevocationAuditWithStates() {
        UserRoleAssignedEvent assigned = UserRoleAssignedEvent.of(
                "user-88",
                "admin-02",
                "ADMIN",
                "corr-assigned",
                "[\"DOCTOR\"]",
                "[\"DOCTOR\",\"PATIENT\"]");

        listener.on(assigned);

        UserRoleRevokedEvent revoked = UserRoleRevokedEvent.of(
                "user-99",
                "admin-03",
                "ADMIN",
                "corr-revoked",
                "[\"DOCTOR\",\"PATIENT\"]",
                "[\"DOCTOR\"]");

        listener.on(revoked);

        verify(repository, times(2)).save(auditCaptor.capture());
        var savedEvents = auditCaptor.getAllValues();

        AuditEvent assignedAudit = savedEvents.get(0);
        assertThat(assignedAudit.getAction()).isEqualTo(AuditAction.ROL_ASIGNADO);
        assertThat(assignedAudit.getActorId()).isEqualTo("admin-02");
        assertThat(assignedAudit.getBeforeState()).isEqualTo("[\"DOCTOR\"]");
        assertThat(assignedAudit.getAfterState()).isEqualTo("[\"DOCTOR\",\"PATIENT\"]");

        AuditEvent revokedAudit = savedEvents.get(1);
        assertThat(revokedAudit.getAction()).isEqualTo(AuditAction.ROL_REVOCADO);
        assertThat(revokedAudit.getBeforeState()).isEqualTo("[\"DOCTOR\",\"PATIENT\"]");
        assertThat(revokedAudit.getAfterState()).isEqualTo("[\"DOCTOR\"]");
    }

    @Test
    void onUserAccountStatusChangedShouldAuditActivationAndDeactivationOfThePatientAccountByPatientId() {
        listener.on(UserAccountStatusChangedEvent.of("patient-10", "admin-01", "[ADMIN]", "corr-on", false, true));
        listener.on(UserAccountStatusChangedEvent.of("patient-11", "admin-01", "[ADMIN]", "corr-off", true, false));

        verify(repository, times(2)).save(auditCaptor.capture());
        var savedEvents = auditCaptor.getAllValues();

        AuditEvent activation = savedEvents.get(0);
        assertThat(activation.getAction()).isEqualTo(AuditAction.USUARIO_ACTIVADO);
        assertThat(activation.getActorId()).isEqualTo("admin-01");
        assertThat(activation.getActorRole()).isEqualTo("[ADMIN]");
        assertThat(activation.getTargetEntityType()).isEqualTo("Paciente");
        assertThat(activation.getTargetEntityId()).isEqualTo("patient-10");
        assertThat(activation.getOutcome()).isEqualTo(AuditOutcome.EXITOSO);
        assertThat(activation.getCorrelationId()).isEqualTo("corr-on");
        assertThat(activation.getBeforeState()).isEqualTo("{\"enabled\":false}");
        assertThat(activation.getAfterState()).isEqualTo("{\"enabled\":true}");

        AuditEvent deactivation = savedEvents.get(1);
        assertThat(deactivation.getAction()).isEqualTo(AuditAction.USUARIO_DESACTIVADO);
        assertThat(deactivation.getTargetEntityType()).isEqualTo("Paciente");
        assertThat(deactivation.getTargetEntityId()).isEqualTo("patient-11");
        assertThat(deactivation.getBeforeState()).isEqualTo("{\"enabled\":true}");
        assertThat(deactivation.getAfterState()).isEqualTo("{\"enabled\":false}");
    }
}
