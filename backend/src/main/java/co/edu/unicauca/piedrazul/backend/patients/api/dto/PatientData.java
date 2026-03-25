package co.edu.unicauca.piedrazul.backend.patients.api.dto;

import co.edu.unicauca.piedrazul.backend.patients.domain.DocumentType;
import co.edu.unicauca.piedrazul.backend.patients.domain.Gender;

import java.time.LocalDate;
import java.util.UUID;

public record PatientData(
        UUID id,
        UUID userId,
        DocumentType documentType,
        String documentNumber,
        String firstName,
        String lastName,
        String phone,
        String email,
        Gender gender,
        LocalDate birthDate,
        String guardianPhone
) {}