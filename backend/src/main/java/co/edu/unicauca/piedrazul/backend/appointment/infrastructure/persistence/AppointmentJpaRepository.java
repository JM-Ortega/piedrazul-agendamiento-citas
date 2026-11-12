package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.persistence;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentState;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.SchedulingOrigin;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.persistence.entity.AppointmentEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AppointmentJpaRepository extends JpaRepository<AppointmentEntity, UUID>, JpaSpecificationExecutor<AppointmentEntity> {

    /** SELECT ... FOR UPDATE: serializa las transiciones de estado de una misma cita. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AppointmentEntity a where a.idAppointment = :id")
    Optional<AppointmentEntity> findByIdForUpdate(@Param("id") UUID id);

    /** Actualización condicional atómica: solo cambia la fila si el estado persistido sigue siendo {@code expected}. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update AppointmentEntity a set a.appointmentState = :target "
            + "where a.idAppointment = :id and a.appointmentState = :expected")
    int updateStateIfCurrent(@Param("id") UUID id,
                             @Param("expected") AppointmentState expected,
                             @Param("target") AppointmentState target);

    /**
     * Toma un bloqueo advisory a nivel de transacción. La función de PostgreSQL devuelve
     * {@code void}, que Hibernate no sabe mapear: se convierte a texto para poder ejecutarla
     * como consulta. La clave es el hash de 64 bits (en el servidor) de un nombre con espacio de
     * nombres, p. ej. {@code piedrazul:appointment:doctor:<uuid>}.
     */
    @Query(value = "select cast(pg_advisory_xact_lock(hashtextextended(:lockName, 0)) as text)", nativeQuery = true)
    String acquireTransactionAdvisoryLock(@Param("lockName") String lockName);

    List<AppointmentEntity> findByIdPatient(UUID idPatient);

    List<AppointmentEntity> findByDate(LocalDate date);

    List<AppointmentEntity> findByIdDoctorAndDate(UUID idDoctor, LocalDate date);

    List<AppointmentEntity> findByIdDoctorAndDateAndAppointmentState(UUID idDoctor, LocalDate date, AppointmentState appointmentState);

    List<AppointmentEntity> findByIdDoctorAndAppointmentState(UUID idDoctor, AppointmentState appointmentState);

    List<AppointmentEntity> findByIdPatientAndDate(UUID idPatient, LocalDate date);

    List<AppointmentEntity> findByAppointmentStateAndDateBefore(AppointmentState state, LocalDate date);

    boolean existsByIdPatientAndAppointmentStateIn(UUID idPatient, Collection<AppointmentState> appointmentStates);

    boolean existsByIdDoctorAndAppointmentStateAndDate(UUID idDoctor, AppointmentState state, LocalDate date);

    boolean existsByIdAppointmentAndAppointmentState(UUID idAppointment, AppointmentState state);

    List<AppointmentEntity> findByIdDoctorAndDateBetween(UUID idDoctor, LocalDate dateStart, LocalDate dateEnd);

    long countByDateAndAppointmentState(LocalDate date, AppointmentState state);

    @Query("""
            select extract(month from a.date) as monthNumber, a.idDoctor as groupKey, count(a) as total
            from AppointmentEntity a
            where a.date >= :start and a.date < :end and a.appointmentState in :states
            group by extract(month from a.date), a.idDoctor
            """)
    List<MonthlyCountView> countByMonthAndDoctor(@Param("start") LocalDate start, @Param("end") LocalDate end,
                                                 @Param("states") Collection<AppointmentState> states);

    @Query("""
            select extract(month from a.date) as monthNumber, a.specialty as groupKey, count(a) as total
            from AppointmentEntity a
            where a.date >= :start and a.date < :end and a.appointmentState in :states
            group by extract(month from a.date), a.specialty
            """)
    List<MonthlyCountView> countByMonthAndSpecialty(@Param("start") LocalDate start, @Param("end") LocalDate end,
                                                    @Param("states") Collection<AppointmentState> states);

    @Query("""
            select extract(month from a.date) as monthNumber, a.appointmentState as state, count(a) as total
            from AppointmentEntity a
            where a.date >= :start and a.date < :end
            group by extract(month from a.date), a.appointmentState
            """)
    List<MonthlyStateCountView> countByMonthAndState(@Param("start") LocalDate start, @Param("end") LocalDate end);

    @Query("""
            select a.idDoctor as doctorId, count(a) as total
            from AppointmentEntity a
            where a.date = :date and a.appointmentState <> :excludedState
            group by a.idDoctor
            """)
    List<DoctorDailyCountView> countByDateGroupedByDoctorExcludingState(@Param("date") LocalDate date,
                                                                        @Param("excludedState") AppointmentState excludedState);
}
