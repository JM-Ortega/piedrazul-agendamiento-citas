package co.edu.unicauca.piedrazul.backend.appointment.domain.port.input;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;

import java.util.List;
import java.util.UUID;

public interface ListMyAppointmentsUseCase {
    List<Appointment> execute(UUID userId);
}