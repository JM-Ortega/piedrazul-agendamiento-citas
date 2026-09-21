package co.edu.unicauca.piedrazul.backend.medicalCheckup.api.dto.output;

import java.time.LocalDate;
import java.util.UUID;

public record MedicalCheckupResponse(
        UUID idClinicalHistory,
        LocalDate attendedAt,
        String doctorName,
        String description
) {}