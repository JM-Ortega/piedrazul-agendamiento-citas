package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentState;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.SchedulingOrigin;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Specialty;

import java.time.LocalDate;
import java.util.UUID;

public record AppointmentResponse(
        UUID idAppointment,
        UUID idDoctor,
        String patientName,
        String patientPhone,
        String documentNumber,
        Specialty specialty,
        LocalDate date,
        String startTime,
        AppointmentState appointmentState,
        SchedulingOrigin schedulingOrigin
) {}
