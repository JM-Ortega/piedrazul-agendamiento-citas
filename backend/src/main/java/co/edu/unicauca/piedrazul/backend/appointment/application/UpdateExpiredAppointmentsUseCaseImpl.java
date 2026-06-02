package co.edu.unicauca.piedrazul.backend.appointment.application;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentState;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.UpdateExpiredAppointmentsUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Service
public class UpdateExpiredAppointmentsUseCaseImpl implements UpdateExpiredAppointmentsUseCase {

    private final AppointmentRepository appointmentRepository;

    public UpdateExpiredAppointmentsUseCaseImpl(AppointmentRepository appointmentRepository) {
        this.appointmentRepository = appointmentRepository;
    }

    @Override
    public void updateExpiredAppointments() {
        List<Appointment> appointments = appointmentRepository.findScheduledAppointmentsBefore(LocalDate.now());

        //el forEach hace que por cada elemento de la lista appointments se le apliquen
        //los dos metodos
        appointments.forEach(appointment -> {
            appointment.changeState(AppointmentState.NO_ASISTIO);
            appointmentRepository.save(appointment);
        });
    }
}
