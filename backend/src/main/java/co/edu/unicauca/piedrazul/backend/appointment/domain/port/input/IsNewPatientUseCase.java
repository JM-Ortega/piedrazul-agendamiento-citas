package co.edu.unicauca.piedrazul.backend.appointment.domain.port.input;

import java.util.UUID;

public interface IsNewPatientUseCase {
    boolean isNewPatient(UUID patientId);
}