package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.persistence;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentState;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.SchedulingOrigin;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.persistence.entity.AppointmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface AppointmentJpaRepository extends JpaRepository<AppointmentEntity, UUID>, JpaSpecificationExecutor<AppointmentEntity> {

    List<AppointmentEntity> findByIdPatient(UUID idPatient);

    List<AppointmentEntity> findByDate(LocalDate date);

    List<AppointmentEntity> findByIdDoctorAndDate(UUID idDoctor, LocalDate date);

    List<AppointmentEntity> findByIdDoctorAndDateAndAppointmentState(UUID idDoctor, LocalDate date, AppointmentState appointmentState);

    List<AppointmentEntity> findByIdDoctorAndAppointmentState(UUID idDoctor, AppointmentState appointmentState);

    List<AppointmentEntity> findByIdPatientAndDate(UUID idPatient, LocalDate date);

    List<AppointmentEntity> findByAppointmentStateAndDateBefore(AppointmentState state, LocalDate date);

    boolean existsByIdPatientAndAppointmentStateIn(UUID idPatient, Collection<AppointmentState> appointmentStates);

    boolean existsByIdDoctorAndAppointmentStateAndDate(UUID idDoctor, AppointmentState state, LocalDate date);

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
}
