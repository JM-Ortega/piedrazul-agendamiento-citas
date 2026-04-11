package co.edu.unicauca.piedrazul.backend.appointment.domain.port.output;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AppointmentRepository {

    // Metodos que solo hablan en terminos del dominio
    void save(Appointment appointment);

    Optional<Appointment> findById(UUID id);

    //metodo para el modulo de historia clinica
    List<Appointment> findByDoctorId(UUID idDoctor);

    List<Appointment> findByPatientId(UUID idPatient);

    List<Appointment> findByDate(LocalDate date);

    List<Appointment> findByDoctorIdAndDate(UUID idDoctor, LocalDate date);

    List<Appointment> findByPatientIdAndDate(UUID idPatient, LocalDate date);

    List<Appointment> findByDoctorIdAndPatientId(UUID idDoctor, UUID idPatient);

    List<Appointment> findByDoctorIdAndPatientIdAndDate(UUID idDoctor, UUID idPatient, LocalDate date);

    List<Appointment> findAll();



}
