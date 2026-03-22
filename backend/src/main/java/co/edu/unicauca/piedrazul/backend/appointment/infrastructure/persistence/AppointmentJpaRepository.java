package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.persistence;

import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.persistence.entity.AppointmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

public interface AppointmentJpaRepository extends JpaRepository<AppointmentEntity, UUID> {

    List<AppointmentEntity> findByIdDoctor(UUID idDoctor);

    List<AppointmentEntity> findByDate(LocalDate date);

    List<AppointmentEntity> findByIdDoctorAndDate(UUID idDoctor, LocalDate date);

    boolean existsByIdDoctorAndDateAndStartTime(UUID idDoctor, LocalDate date, LocalTime startTime);
}
