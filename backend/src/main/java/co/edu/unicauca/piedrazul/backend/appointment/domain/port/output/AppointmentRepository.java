package co.edu.unicauca.piedrazul.backend.appointment.domain.port.output;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentState;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface AppointmentRepository {

    // Metodos que solo hablan en terminos del dominio
    Appointment save(Appointment appointment);


    List<Appointment> findByDoctorId(UUID idDoctor);

    List<Appointment> findByPatientId(UUID idPatient);

    List<Appointment> findByDate(LocalDate date);

    List<Appointment> findByDoctorIdAndDate(UUID idDoctor, LocalDate date);

    List<Appointment> findByDoctorIdAndDateAndState(UUID idDoctor, LocalDate date, String state);

    List<Appointment> findByPatientIdAndDate(UUID idPatient, LocalDate date);

    List<Appointment> findByDoctorIdAndPatientId(UUID idDoctor, UUID idPatient);

    List<Appointment> findByDoctorIdAndPatientIdAndDate(UUID idDoctor, UUID idPatient, LocalDate date);

    boolean existsByPatientIdAndStates(UUID idPatient, Collection<AppointmentState> states);

    List<Appointment> findAll();

    Appointment findById(UUID appointmentId);

    UUID getPattientIdByAppointmentId(UUID appointmentId);

}
