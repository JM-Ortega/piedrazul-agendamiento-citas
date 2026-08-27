package co.edu.unicauca.piedrazul.backend.appointment.domain.port.input;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentState;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.PageQuery;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.PagedResult;

import java.time.LocalDate;
import java.util.UUID;

public interface ListAppointmentsUseCase {
    PagedResult<Appointment> listBy(UUID idDoctor, UUID idPatient, LocalDate date, AppointmentState state, PageQuery pageQuery);
}
