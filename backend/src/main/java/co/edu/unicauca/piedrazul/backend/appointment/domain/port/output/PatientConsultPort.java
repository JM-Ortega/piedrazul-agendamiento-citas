package co.edu.unicauca.piedrazul.backend.appointment.domain.port.output;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.PatientInfo;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.PatientSnapshot;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.PatientRegistrationData;

import java.util.Optional;
import java.util.UUID;

public interface PatientConsultPort {
    // Devuelve el PatientInfo snapshot para construir la cita
    PatientInfo findById(UUID idPatient);

    Optional<PatientSnapshot> findByDocumentNumber(String documentNumber);

    UUID createPatient(PatientRegistrationData data);
}
