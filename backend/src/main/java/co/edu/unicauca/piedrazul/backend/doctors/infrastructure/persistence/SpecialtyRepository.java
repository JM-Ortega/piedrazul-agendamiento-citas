package co.edu.unicauca.piedrazul.backend.doctors.infrastructure.persistence;

import co.edu.unicauca.piedrazul.backend.doctors.domain.Specialty;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpecialtyRepository extends JpaRepository<Specialty, String> {
}