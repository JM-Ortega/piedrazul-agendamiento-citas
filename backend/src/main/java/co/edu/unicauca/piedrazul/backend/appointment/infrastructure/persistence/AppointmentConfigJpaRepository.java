package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.persistence;

import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.persistence.entity.AppointmentConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppointmentConfigJpaRepository extends JpaRepository<AppointmentConfigEntity, Integer> {
}
