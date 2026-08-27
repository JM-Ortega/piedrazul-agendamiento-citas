package co.edu.unicauca.piedrazul.backend.clinicalHistory.api.dto.output;

import java.time.LocalDate;
import java.util.UUID;

public record ClinicalHistoryResponse(
        UUID idClinicalHistory,
        LocalDate attendedAt,
        String doctorName,
        String description
) {}