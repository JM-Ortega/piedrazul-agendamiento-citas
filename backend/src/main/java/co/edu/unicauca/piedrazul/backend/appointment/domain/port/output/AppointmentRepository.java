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

    //metodo para el modulo de historia clinica
    List<Appointment> findByDoctorId(UUID idDoctor);

    List<Appointment> findByPatientId(UUID idPatient);

    List<Appointment> findByDate(LocalDate date);

    List<Appointment> findByDoctorIdAndDate(UUID idDoctor, LocalDate date);

    List<Appointment> findByDoctorIdAndDateAndState(UUID idDoctor, LocalDate date, String state);

    //metodo para listar y usado en algo más
    List<Appointment> findByPatientIdAndDate(UUID idPatient, LocalDate date);

    //metodo para listar citas
    List<Appointment> findByDoctorIdAndPatientId(UUID idDoctor, UUID idPatient);

    //metodo para listar citas
    List<Appointment> findByDoctorIdAndPatientIdAndDate(UUID idDoctor, UUID idPatient, LocalDate date);

    boolean existsByPatientIdAndStates(UUID idPatient, Collection<AppointmentState> states);

    List<Appointment> findAll();

    Appointment findById(UUID appointmentId);
  
    List<Appointment> findAllByDate(LocalDate date);

    List<Appointment> findScheduledAppointmentsBefore(LocalDate date);

    UUID getPattientIdByAppointmentId(UUID appointmentId);

    //Metodo unico para el caso de uso ListAppointmentsUseCase
    List<Appointment> listBy(UUID idDoctor, UUID idPatient, LocalDate date, AppointmentState state);

}
