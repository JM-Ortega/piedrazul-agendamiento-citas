package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.internal;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentTime;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.PatientInfo;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Specialty;

import java.time.LocalDate;
import java.util.UUID;

// No es un DTO de infraestructura ni de controlador. Es simplemente un objeto que agrupa los datos necesarios
// para crear una cita.
public record AppointmentSchedulingRequest(
        UUID idDoctor,
        String doctorName,
        UUID idPatient,
        String patientName,
        PatientInfo patientInfo,
        Specialty specialty,
        LocalDate date,
        AppointmentTime startTime
) {
}