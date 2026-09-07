package co.edu.unicauca.piedrazul.backend.doctors.infrastructure.persistence;

import co.edu.unicauca.piedrazul.backend.doctors.domain.Schedule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ScheduleRepository extends JpaRepository<Schedule, UUID> {
}