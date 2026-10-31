package co.edu.unicauca.piedrazul.backend.appointment.application;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentState;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.CountScheduledAppointmentsUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentRepository;

import java.time.LocalDate;

public class CountScheduledAppointmentsUseCaseImpl implements CountScheduledAppointmentsUseCase {

    private final AppointmentRepository appointmentRepository;

    public CountScheduledAppointmentsUseCaseImpl(AppointmentRepository appointmentRepository) {
        this.appointmentRepository = appointmentRepository;
    }

    @Override
    public long execute(LocalDate date){
        return appointmentRepository.countByDateAndState(date, AppointmentState.AGENDADA);
    }
}
