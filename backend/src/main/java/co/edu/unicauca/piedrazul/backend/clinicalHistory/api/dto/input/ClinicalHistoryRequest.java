package co.edu.unicauca.piedrazul.backend.clinicalHistory.api.dto.input;

import java.util.UUID;

public record ClinicalHistoryRequest(
        UUID idAppointment,
        String description
) {}
