package co.edu.unicauca.piedrazul.backend.appointment.events;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * Una cita se reagendó: {@code previousAppointmentId} quedó cancelada y {@code newAppointmentId}
 * la reemplaza. Se publica al final de la transacción del reagendamiento; no hay evento de
 * cancelación aparte.
 */
public record AppointmentRescheduledEvent(
        UUID previousAppointmentId,
        UUID newAppointmentId,
        UUID patientId,
        String patientName,
        String patientPhone,
        String patientEmail,
        String previousDoctorName,
        LocalDate previousDate,
        LocalTime previousTime,
        String previousSpecialty,
        String doctorName,
        LocalDate appointmentDate,
        LocalTime appointmentTime,
        String specialty
) {
}
