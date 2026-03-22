package co.edu.unicauca.piedrazul.backend.appointment.aplication;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.ListAppointmentsUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public class ListAppointmentsUseCaseImpl implements ListAppointmentsUseCase {
    private final AppointmentRepository appointmentRepository;

    public ListAppointmentsUseCaseImpl(AppointmentRepository appointmentRepository) {
        this.appointmentRepository = appointmentRepository;
    }

    @Override
    public List<Appointment> listByIdDoctor(UUID idDoctor) {
        return appointmentRepository.findByDoctorId(idDoctor);
    }

    //Lista citas por id del médico
    @Override
    public List<Appointment> listByDoctorId(UUID idDoctor) {
        return appointmentRepository.findByDoctorId(idDoctor);
    }

    // Listar citas de un médico en una fecha
    @Override
    public List<Appointment> listByDoctorAndDate(UUID idDoctor, LocalDate date) {
        return appointmentRepository.findByDoctorIdAndDate(idDoctor, date);
    }


    //Listar citas por una fecha en especifico
    @Override
    public List<Appointment> listByDate(LocalDate date) {
        return appointmentRepository.findByDate(date);
    }

}
