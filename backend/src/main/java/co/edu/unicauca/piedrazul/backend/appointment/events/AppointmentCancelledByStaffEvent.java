package co.edu.unicauca.piedrazul.backend.appointment.events;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * El personal canceló la cita (no el paciente, ni la cancelación interna de un reagendamiento).
 * Se publica dentro de la transacción de la cancelación; solo se consume si esta se confirma.
 */
public record AppointmentCancelledByStaffEvent(
        UUID appointmentId,
        UUID patientId,
        String patientName,
        String patientPhone,
        String patientEmail,
        String doctorName,
        LocalDate appointmentDate,
        LocalTime appointmentTime,
        String specialty
) {
}
