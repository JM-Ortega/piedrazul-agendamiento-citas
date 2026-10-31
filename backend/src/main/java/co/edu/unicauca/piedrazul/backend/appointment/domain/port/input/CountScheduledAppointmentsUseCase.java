package co.edu.unicauca.piedrazul.backend.appointment.domain.port.input;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentState;

import java.time.LocalDate;

public interface CountScheduledAppointmentsUseCase {
    long execute(LocalDate date);
}
