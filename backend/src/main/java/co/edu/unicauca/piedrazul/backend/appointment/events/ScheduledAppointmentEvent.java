package co.edu.unicauca.piedrazul.backend.appointment.events;

import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.persistence.entity.AppointmentEntity;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Evento publicado cuando una cita queda agendada exitosamente.
 * Es para la auditoria
 */
public record ScheduledAppointmentEvent(
        UUID citaId,
        String username,
        String rol,
        String correlationId
) {
    public static ScheduledAppointmentEvent of(AppointmentEntity cita, String username, String rol, String correlationId) {
        return new ScheduledAppointmentEvent(
                cita.getIdAppointment(),
                username,
                rol,
                correlationId
        );
    }
}
