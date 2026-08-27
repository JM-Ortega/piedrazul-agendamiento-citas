package co.edu.unicauca.piedrazul.backend.clinicalHistory.api.dto.output;

import java.time.LocalDate;

public record ClinicalHistoryResponse(
        LocalDate attendedAt,
        String doctorName,
        String description
) {}