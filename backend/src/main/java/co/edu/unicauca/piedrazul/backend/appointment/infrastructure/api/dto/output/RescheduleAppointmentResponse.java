package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output;

import java.util.UUID;

/**
 * Resultado de un reagendamiento: la cita original (ya CANCELADA) y la cita nueva.
 */
public record RescheduleAppointmentResponse(
        UUID cancelledAppointmentId,
        AppointmentResponse appointment
) {
}
