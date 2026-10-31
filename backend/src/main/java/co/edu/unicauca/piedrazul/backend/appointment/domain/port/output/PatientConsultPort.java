package co.edu.unicauca.piedrazul.backend.appointment.domain.port.output;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.PatientInfo;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.PatientSnapshot;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Consultas de pacientes. Solo lectura: el alta vive en
 * {@link PatientProvisioningPort}.
 */
public interface PatientConsultPort {
    // Devuelve el PatientInfo snapshot para construir la cita
    PatientInfo findById(UUID idPatient);

    Optional<PatientSnapshot> findByDocumentNumber(String documentNumber);

    Optional<PatientSnapshot> findByUserId(UUID userId);

    Map<UUID, PatientInfo> findByIds(Set<UUID> idPatients);

    boolean existsById(UUID idPatient);
}
