package co.edu.unicauca.piedrazul.backend.patients.api.dto.internal;

import co.edu.unicauca.piedrazul.backend.patients.api.PatientDocumentType;
import co.edu.unicauca.piedrazul.backend.patients.api.PatientGender;

import java.time.LocalDate;
import java.util.UUID;

public record PatientData(
        UUID id,
        UUID userId,
        PatientDocumentType documentType,
        String documentNumber,
        String firstName,
        String lastName,
        String phone,
        String email,
        PatientGender gender,
        LocalDate birthDate,
        String guardianPhone
) {}