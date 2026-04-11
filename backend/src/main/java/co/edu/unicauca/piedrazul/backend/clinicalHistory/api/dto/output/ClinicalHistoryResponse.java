package co.edu.unicauca.piedrazul.backend.clinicalHistory.api.dto.output;

import java.time.LocalDateTime;
import java.util.UUID;

public record ClinicalHistoryResponse(
        UUID idClinicalHistory,
        UUID idAppointment,
        UUID idDoctor,
        String doctorName,
        UUID idPatient,
        LocalDateTime attendedAt,
        String description
) {}