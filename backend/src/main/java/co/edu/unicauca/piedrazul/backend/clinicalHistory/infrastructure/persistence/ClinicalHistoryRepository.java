package co.edu.unicauca.piedrazul.backend.clinicalHistory.infrastructure.persistence;

import co.edu.unicauca.piedrazul.backend.clinicalHistory.domain.ClinicalHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ClinicalHistoryRepository extends JpaRepository<ClinicalHistory, UUID> {

    //Para consultar la HC de una cita especifica
    Optional<ClinicalHistory> findByIdAppointment(UUID idAppointment);

    //Para consultar el HC completo de un paciente
    List<ClinicalHistory> findByIdPatient(UUID idPatient);

    //Para validar si existe una HC para una cita especifica y evitar crear mas de uno
    boolean existsByIdAppointment(UUID idAppointment);
}