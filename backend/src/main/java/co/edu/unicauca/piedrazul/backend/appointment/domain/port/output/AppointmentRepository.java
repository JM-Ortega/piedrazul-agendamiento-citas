package co.edu.unicauca.piedrazul.backend.appointment.domain.port.output;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.*;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface AppointmentRepository {

    // Metodos que solo hablan en terminos del dominio
    Appointment save(Appointment appointment);

    List<Appointment> findByPatientId(UUID idPatient);

    List<Appointment> findByDoctorIdAndDate(UUID idDoctor, LocalDate date);

    List<Appointment> findByDoctorIdAndDateAndState(UUID idDoctor, LocalDate date, String state);

    List<Appointment> findByDoctorIdAndState(UUID idDoctor, String state);

    List <Appointment> findByDoctorAndDateBetween (UUID idDoctor, LocalDate dateStart, LocalDate dateEnd);

    //metodo para listar y usado en algo más
    List<Appointment> findByPatientIdAndDate(UUID idPatient, LocalDate date);

    boolean existsByPatientIdAndStates(UUID idPatient, Collection<AppointmentState> states);

    Appointment findById(UUID appointmentId);
  
    List<Appointment> findAllByDate(LocalDate date);

    List<Appointment> findScheduledAppointmentsBefore(LocalDate date);

    UUID getPattientIdByAppointmentId(UUID appointmentId);

    //Metodo unico para el caso de uso ListAppointmentsUseCase
    PagedResult<Appointment> listBy(UUID idDoctor, UUID idPatient, LocalDate date, AppointmentState state, PageQuery pageQuery);

    boolean existsByIdPatientAndSchedulingOriginAndDateBetween(UUID idPatient, SchedulingOrigin schedulingOrigin,
                                                               LocalDate startDate, LocalDate endDate);
}
