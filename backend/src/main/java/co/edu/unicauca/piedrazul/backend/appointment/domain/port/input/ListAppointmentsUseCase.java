package co.edu.unicauca.piedrazul.backend.appointment.domain.port.input;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentState;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface ListAppointmentsUseCase {
    List<Appointment> listBy(UUID idDoctor, UUID idPatient, LocalDate date, AppointmentState state);
}
