package co.edu.unicauca.piedrazul.backend.appointment.application;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.ListAppointmentsUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentRepository;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentState;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public class ListAppointmentsUseCaseImpl implements ListAppointmentsUseCase {
    private final AppointmentRepository appointmentRepository;

    public ListAppointmentsUseCaseImpl(AppointmentRepository appointmentRepository) {
        this.appointmentRepository = appointmentRepository;
    }

    // POLIMORFISMO
    @Override
    public List<Appointment> listBy(UUID idDoctor, UUID idPatient, LocalDate date, AppointmentState state ) {
        return appointmentRepository.listBy(idDoctor, idPatient, date, state);
    }
}
