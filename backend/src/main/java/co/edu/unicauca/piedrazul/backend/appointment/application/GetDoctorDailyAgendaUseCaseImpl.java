package co.edu.unicauca.piedrazul.backend.appointment.application;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentState;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.PageQuery;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.PagedResult;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.GetDoctorDailyAgendaUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentRepository;

import java.time.LocalDate;
import java.util.UUID;

public class GetDoctorDailyAgendaUseCaseImpl implements GetDoctorDailyAgendaUseCase {
    private final AppointmentRepository appointmentRepository;

    public GetDoctorDailyAgendaUseCaseImpl(AppointmentRepository appointmentRepository) {
        this.appointmentRepository = appointmentRepository;
    }

    @Override
    public PagedResult<Appointment> execute(UUID idDoctor, LocalDate date, AppointmentState state, PageQuery pageQuery) {
        return appointmentRepository.ListDoctorDailyAgenda(idDoctor, date, state, pageQuery);
    }
}
