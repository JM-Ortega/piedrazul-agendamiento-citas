package co.edu.unicauca.piedrazul.backend.clinicalHistory.api.dto.input;

import java.time.LocalDate;
import java.util.UUID;

public record ClinicalHistoryRequest(
        UUID patientId,
        UUID appointmentId,
        String description,
        String doctorName,
        LocalDate attendedAt
) {}
