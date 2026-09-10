package co.edu.unicauca.piedrazul.backend.appointment.domain.port.input;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentState;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.PageQuery;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.PagedResult;

import java.time.LocalDate;
import java.util.UUID;

public interface GetDoctorDailyAgendaUseCase {
    PagedResult<Appointment> execute(UUID idDoctor, LocalDate date, AppointmentState state, PageQuery pageQuery);
}
