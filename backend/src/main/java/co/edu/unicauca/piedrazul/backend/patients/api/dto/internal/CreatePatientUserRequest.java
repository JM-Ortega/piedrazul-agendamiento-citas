package co.edu.unicauca.piedrazul.backend.patients.api.dto.internal;

import co.edu.unicauca.piedrazul.backend.jackson.sanitization.Sanitize;
import co.edu.unicauca.piedrazul.backend.patients.api.PatientDocumentType;
import co.edu.unicauca.piedrazul.backend.patients.api.PatientGender;
import jakarta.validation.constraints.*;

import java.time.LocalDate;


public record CreatePatientUserRequest(
        @NotNull
        PatientDocumentType documentType,

        // Número Colombiano
        @Pattern(regexp = "^[0-9]{10}$")
        @NotBlank
        @Sanitize
        String phone,

        @NotNull
        PatientGender gender,

        @NotNull
        LocalDate birthDate,

        // Número Colombiano
        @Pattern(regexp = "^[0-9]{10}$")
        @Sanitize
        String guardianPhone

) {
}
