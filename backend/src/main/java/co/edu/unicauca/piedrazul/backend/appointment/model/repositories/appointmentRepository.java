package co.edu.unicauca.piedrazul.backend.appointment.model.repositories;

import co.edu.unicauca.piedrazul.backend.appointment.model.models.appointment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface appointmentRepository extends JpaRepository<appointment, Long> {

    List<appointment> findByIdDoctor(UUID idDoctor);

    List<appointment> findByDateOrderByStartTimeAsc(LocalDate date);

    List<appointment> findByIdDoctorAndDateOrderByStartTimeAsc(UUID idDoctor, LocalDate date);

}

