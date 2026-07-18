package co.edu.unicauca.piedrazul.backend.audit.application;

import co.edu.unicauca.piedrazul.backend.audit.domain.AuditAction;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditModule;
import co.edu.unicauca.piedrazul.backend.shared.events.audit.AppointmentCreatedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AuditEventListenerTest {

    @Mock
    private AuditLogService auditLogService;

    @Test
    void onAppointmentCreatedShouldForwardAppointmentEventToAuditService() {
        AuditEventListener listener = new AuditEventListener(auditLogService);
        UUID appointmentId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        UUID performedBy = UUID.fromString("44444444-4444-4444-4444-444444444444");

        listener.onAppointmentCreated(new AppointmentCreatedEvent(appointmentId, performedBy));

        ArgumentCaptor<UUID> entityIdCaptor = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<UUID> performedByCaptor = ArgumentCaptor.forClass(UUID.class);

        verify(auditLogService).register(
            eq(AuditAction.APPOINTMENT_CREATED),
            eq(AuditModule.APPOINTMENTS),
                entityIdCaptor.capture(),
                performedByCaptor.capture()
        );

        assertThat(entityIdCaptor.getValue()).isEqualTo(appointmentId);
        assertThat(performedByCaptor.getValue()).isEqualTo(performedBy);
    }
}