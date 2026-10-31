package co.edu.unicauca.piedrazul.backend.appointment.domain.port.output;

import java.time.LocalDate;
import java.util.UUID;

public interface ClinicalHistoryPort {
    void registerClinicalHistory(
            UUID appointmentId,
            UUID idPatient,
            String doctorName,
            String description,
            LocalDate attendedAt
    );
}