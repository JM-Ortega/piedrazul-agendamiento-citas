package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.internal;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentTime;
import co.edu.unicauca.piedrazul.backend.shared.enums.SpecialtyCode;

import java.time.LocalDate;
import java.util.UUID;

// No es un DTO de infraestructura ni de controlador. Es simplemente un objeto que agrupa los datos necesarios
// para crear una cita.
public record AppointmentSchedulingRequest(
        UUID idDoctor,
        UUID idPatient,
        SpecialtyCode specialty,
        LocalDate date,
        AppointmentTime startTime
) {
}