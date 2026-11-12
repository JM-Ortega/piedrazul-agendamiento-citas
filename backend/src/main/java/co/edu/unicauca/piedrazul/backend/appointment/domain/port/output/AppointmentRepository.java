package co.edu.unicauca.piedrazul.backend.appointment.domain.port.output;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.*;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.statistics.DoctorDailyCount;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.statistics.MonthlyGroupCount;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.statistics.MonthlyStateCount;

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

    boolean existsByDoctorAndState(UUID idDoctor, AppointmentState state);

    boolean existsByIdAndState(UUID appointmentId, AppointmentState state);

    Appointment findById(UUID appointmentId);

    /**
     * Igual que {@link #findById(UUID)} (misma excepción si no existe), pero toma un bloqueo de
     * escritura sobre la fila hasta el fin de la transacción. Las transiciones de estado lo usan
     * para que dos operaciones concurrentes sobre la misma cita no puedan ganar ambas.
     * Exige una transacción activa.
     */
    Appointment findByIdForUpdate(UUID appointmentId);

    /**
     * Pasa la cita a NO_ASISTIO solo si en base de datos sigue AGENDADA al momento de la
     * actualización (una única sentencia atómica).
     *
     * @return {@code true} si la fila cambió; {@code false} si otra transición ya la había
     *         movido de estado (no se sobrescribe nada)
     */
    boolean markUnassistedIfScheduled(UUID appointmentId);

    /**
     * Bloqueo consultivo (advisory) de la agenda del doctor, retenido hasta el commit/rollback de
     * la transacción actual. Debe tomarse antes de leer las citas del doctor. Exige una
     * transacción activa. Orden obligatorio: doctor y luego paciente.
     */
    void lockDoctorSchedule(UUID idDoctor);

    /**
     * Bloqueo consultivo (advisory) del paciente, retenido hasta el commit/rollback de la
     * transacción actual. Debe tomarse antes de evaluar las reglas que dependen del paciente.
     * Exige una transacción activa. Orden obligatorio: doctor y luego paciente.
     */
    void lockPatientSchedule(UUID idPatient);
  
    List<Appointment> findAllByDate(LocalDate date);

    List<Appointment> findScheduledAppointmentsBefore(LocalDate date);

    UUID getPattientIdByAppointmentId(UUID appointmentId);

    //Metodo unico para el caso de uso ListAppointmentsUseCase
    PagedResult<Appointment> listBy(UUID idDoctor, UUID idPatient, LocalDate date, AppointmentState state, PageQuery pageQuery);

    PagedResult<Appointment> ListDoctorDailyAgenda(UUID idDoctor, LocalDate date, PageQuery pageQuery);

    long countByDateAndState(LocalDate date, AppointmentState state);

    List<MonthlyGroupCount> countByMonthAndDoctor(int year, Collection<AppointmentState> states);

    List<MonthlyGroupCount> countByMonthAndSpecialty(int year, Collection<AppointmentState> states);

    List<MonthlyStateCount> countByMonthAndState(int year);

    List<DoctorDailyCount> countByDateGroupedByDoctorExcludingState(LocalDate date, AppointmentState excludedState);
}
