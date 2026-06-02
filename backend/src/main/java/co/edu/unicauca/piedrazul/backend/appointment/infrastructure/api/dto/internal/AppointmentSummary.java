package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.internal;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

public record AppointmentSummary(
        UUID idAppointment,
        UUID idPatient,
        String patientFullName,
        String document,
        String phoneNumber,
        UUID idDoctor,
        String doctorName,
        LocalDate date,
        LocalTime startTime,
        String specialty,
        String state
) {
}
