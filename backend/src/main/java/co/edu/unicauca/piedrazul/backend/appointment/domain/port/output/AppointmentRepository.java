package co.edu.unicauca.piedrazul.backend.appointment.domain.port.output;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentTime;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface AppointmentRepository {

    // Metodos que solo hablan en terminos del dominio
    void save(Appointment appointment);


    List<Appointment> findByDoctorId(UUID idDoctor);

    List<Appointment> findByDate(LocalDate date);

    List<Appointment> findByDoctorIdAndDate(UUID idDoctor, LocalDate date);

    List<Appointment> findAll();

}
