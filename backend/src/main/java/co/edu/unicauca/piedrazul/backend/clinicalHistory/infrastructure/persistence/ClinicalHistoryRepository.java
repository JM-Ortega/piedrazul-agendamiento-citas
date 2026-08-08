package co.edu.unicauca.piedrazul.backend.clinicalHistory.infrastructure.persistence;

import co.edu.unicauca.piedrazul.backend.clinicalHistory.domain.ClinicalHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.repository.JpaRepository;

import org.springframework.data.domain.Pageable;
import java.util.UUID;

public interface ClinicalHistoryRepository extends JpaRepository<ClinicalHistory, UUID> {

    //Para consultar el HC completo de un paciente
    Page<ClinicalHistory> findByIdPatient(UUID idPatient, Pageable pageable);

    //Para validar si existe una HC para una cita especifica y evitar crear mas de uno
    boolean existsByIdAppointment(UUID idAppointment);
}