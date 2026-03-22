package co.edu.unicauca.piedrazul.backend.appointment.domain.port.input;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface ListAppointmentsUseCase {
    // Listar citas de un médico en una fecha
    List<Appointment> listByDoctorAndDate(UUID idDoctor, LocalDate date);
}
