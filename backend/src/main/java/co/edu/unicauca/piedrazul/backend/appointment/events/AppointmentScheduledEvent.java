package co.edu.unicauca.piedrazul.backend.appointment.events;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

public record AppointmentScheduledEvent(
        UUID appointmentId,
        UUID patientId,
        String patientName,
        String patientPhone,
        String patientEmail,
        UUID doctorId,
        String doctorName,
        LocalDate appointmentDate,
        LocalTime appointmentTime,
        String specialty,
        UUID performedBy
) {
}