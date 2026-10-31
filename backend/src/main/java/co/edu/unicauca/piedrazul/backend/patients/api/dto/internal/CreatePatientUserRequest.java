package co.edu.unicauca.piedrazul.backend.patients.api.dto.internal;

import co.edu.unicauca.piedrazul.backend.jackson.sanitization.Sanitize;
import co.edu.unicauca.piedrazul.backend.patients.api.PatientSex;
import jakarta.validation.constraints.*;

import java.time.LocalDate;


public record CreatePatientUserRequest(
        @NotNull
        PatientSex sex,

        @NotNull
        LocalDate birthDate,

        // Número Colombiano
        @Pattern(regexp = "^[0-9]{10}$")
        @Sanitize
        String guardianPhone

) {
}
