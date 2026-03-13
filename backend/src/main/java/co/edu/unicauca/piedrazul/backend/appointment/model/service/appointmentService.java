package co.edu.unicauca.piedrazul.backend.appointment.model.service;

import co.edu.unicauca.piedrazul.backend.appointment.controller.dtos.appointmentCreateRequest;
import co.edu.unicauca.piedrazul.backend.appointment.model.models.appointment;
import co.edu.unicauca.piedrazul.backend.appointment.model.models.enumAppointmentState;
import co.edu.unicauca.piedrazul.backend.appointment.model.repositories.appointmentRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
public class appointmentService {

    private final appointmentRepository appointmentRepository;

    public appointmentService(appointmentRepository appointmentRepository) {
        this.appointmentRepository = appointmentRepository;
    }

    public appointment create(appointmentCreateRequest request) {
        appointment newAppointment = new appointment(
                request.idDoctor(),
                request.idPatient(),
                request.specialty(),
                enumAppointmentState.AGENDADA,
                request.date(),
                request.startTime(),
                request.schedulingOrigin()
        );

        return appointmentRepository.save(newAppointment);
    }

    public void delete(long idAppointment) {
        if (!appointmentRepository.existsById(idAppointment)) {
            throw new NoSuchElementException("No existe una cita con id: " + idAppointment);
        }

        appointmentRepository.deleteById(idAppointment);
    }

    public List<appointment> listAll() {
        return appointmentRepository.findAll();
    }

    public List<appointment> listByDoctor(UUID idDoctor) {
        return appointmentRepository.findByIdDoctor(idDoctor);
    }


    public List<appointment> listByDate(LocalDate date) {
        return appointmentRepository.findByDateOrderByStartTimeAsc(date);
    }

    public List<appointment> listByDoctorAndDate(UUID idDoctor, LocalDate date) {
        return appointmentRepository.findByIdDoctorAndDateOrderByStartTimeAsc(idDoctor, date);
    }
}
