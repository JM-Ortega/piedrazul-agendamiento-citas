package co.edu.unicauca.piedrazul.backend.patients.api.dto.internal;

import co.edu.unicauca.piedrazul.backend.patients.api.PatientSex;
import co.edu.unicauca.piedrazul.backend.shared.enums.IdentificationType;

import java.time.LocalDate;
import java.util.UUID;

public record PatientData(
        UUID personId,
        UUID userId,
        IdentificationType identificationType,
        String identification,
        String firstName,
        String lastName,
        String phone,
        String email,
        PatientSex sex,
        LocalDate birthDate,
        String guardianPhone
) {}
