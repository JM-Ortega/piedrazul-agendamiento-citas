package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentState;
import co.edu.unicauca.piedrazul.backend.shared.enums.SpecialtyCode;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

// Solicitado por Nicolle
public record AppointmentResponse(
        UUID idAppointment,
        LocalDate date,
        LocalTime startTime,
        AppointmentState appointmentState,
        String doctorName,
        SpecialtyCode specialty,
        String patientFirstName,
        String patientLastName,
        String documentNumber
) {}
