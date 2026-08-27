package co.edu.unicauca.piedrazul.backend.audit.application;

import co.edu.unicauca.piedrazul.backend.appointment.events.ScheduledAppointmentEvent;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditEvent;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditEventRepository;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditOutcome;
import co.edu.unicauca.piedrazul.backend.clinicalHistory.events.ClinicalHistoryCreatedEvent;
import co.edu.unicauca.piedrazul.backend.shared.enums.AuditAction;
import co.edu.unicauca.piedrazul.backend.user.events.UserActivatedEvent;
import co.edu.unicauca.piedrazul.backend.user.events.UserCreatedEvent;
import co.edu.unicauca.piedrazul.backend.user.events.UserDeactivatedEvent;
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
    void onUserRoleAuditEventShouldPersistActivationAndDeactivationAuditWithStates() {
        UserActivatedEvent activated = UserActivatedEvent.of(
                "user-88",
                "admin-02",
                "ADMIN",
                "corr-activated",
                "[\"DOCTOR\"]",
                "[\"DOCTOR\",\"PATIENT\"]");

        listener.on(activated);

        UserDeactivatedEvent deactivated = UserDeactivatedEvent.of(
                "user-99",
                "admin-03",
                "ADMIN",
                "corr-deactivated",
                "[\"DOCTOR\",\"PATIENT\"]",
                "[\"DOCTOR\"]");

        listener.on(deactivated);

        verify(repository, times(2)).save(auditCaptor.capture());
        var savedEvents = auditCaptor.getAllValues();

        AuditEvent activatedAudit = savedEvents.get(0);
        assertThat(activatedAudit.getAction()).isEqualTo(AuditAction.USUARIO_ACTIVADO);
        assertThat(activatedAudit.getActorId()).isEqualTo("admin-02");
        assertThat(activatedAudit.getBeforeState()).isEqualTo("[\"DOCTOR\"]");
        assertThat(activatedAudit.getAfterState()).isEqualTo("[\"DOCTOR\",\"PATIENT\"]");

        AuditEvent deactivatedAudit = savedEvents.get(1);
        assertThat(deactivatedAudit.getAction()).isEqualTo(AuditAction.USUARIO_DESACTIVADO);
        assertThat(deactivatedAudit.getBeforeState()).isEqualTo("[\"DOCTOR\",\"PATIENT\"]");
        assertThat(deactivatedAudit.getAfterState()).isEqualTo("[\"DOCTOR\"]");
    }
}
