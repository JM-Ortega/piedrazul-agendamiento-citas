package co.edu.unicauca.piedrazul.backend.patients.infrastructure.persistence;

import co.edu.unicauca.piedrazul.backend.patients.domain.Patient;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PatientRepository extends JpaRepository<Patient, UUID> {
}
