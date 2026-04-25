package co.edu.unicauca.piedrazul.backend.appointment;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentState;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

public record AppointmentSummary(
        UUID idAppointment,
        UUID idDoctor,
        UUID idPatient,
        LocalDate date,
        LocalTime starTime,
        AppointmentState state

) {
}
