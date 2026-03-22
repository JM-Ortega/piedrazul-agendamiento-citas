package co.edu.unicauca.piedrazul.backend.appointment.aplication;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.ListAppointmentsUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public class ListAppointmentsUseCaseImpl implements ListAppointmentsUseCase {
    private final AppointmentRepository appointmentRepository;

    public ListAppointmentsUseCaseImpl(AppointmentRepository appointmentRepository) {
        this.appointmentRepository = appointmentRepository;
    }

    // Listar citas de un médico en una fecha
    @Override
    public List<Appointment> listByDoctorAndDate(UUID idDoctor, LocalDate date) {
        return appointmentRepository.findByDoctorIdAndDate(idDoctor, date);
    }
}
