package co.edu.unicauca.piedrazul.backend.appointment.domain.port.output;

import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.PatientRegistrationData;

import javax.swing.text.html.Option;
import java.util.UUID;

public interface PatientRegistryPort {
    // Option<UUID> findPatientByCocumentNumber(String documentNumber);

    UUID createPatient(PatientRegistrationData data);
}
