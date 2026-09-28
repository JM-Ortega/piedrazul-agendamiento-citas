package co.edu.unicauca.piedrazul.backend.medicalCheckup.api.dto.internal;

import java.time.LocalDate;
import java.util.UUID;

public record MedicalCheckupRequest(
        UUID patientId,
        UUID appointmentId,
        String description,
        String doctorName,
        LocalDate attendedAt
) {}
