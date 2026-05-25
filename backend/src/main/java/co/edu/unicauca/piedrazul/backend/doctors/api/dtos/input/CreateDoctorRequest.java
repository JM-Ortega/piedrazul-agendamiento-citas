package co.edu.unicauca.piedrazul.backend.doctors.api.dtos.input;

import co.edu.unicauca.piedrazul.backend.jackson.NormalizeName;
import co.edu.unicauca.piedrazul.backend.jackson.sanitization.Sanitize;
import co.edu.unicauca.piedrazul.backend.jackson.validation.ValidDocument;
import co.edu.unicauca.piedrazul.backend.doctors.domain.DocumentType;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Specialty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.time.LocalDate;
import java.util.List;

@ValidDocument(documentField = "identification", typeField = "documentType")
public record CreateDoctorRequest(
        @Size(min = 2, max = 60)
        @Pattern(regexp = "^[\\p{L} '-]{2,60}$")
        @NotBlank
        @Sanitize
        @NormalizeName
        String firstName,

        @Size(min = 2, max = 60)
        @Pattern(regexp = "^[\\p{L} '-]{2,60}$")
        @NotBlank
        @Sanitize
        @NormalizeName
        String lastName,

        @NotNull
        DocumentType documentType,

        @Size(max = 20)
        @NotBlank
        @Sanitize
        String identification,

        // Número Colombiano
        @Pattern(regexp = "^[0-9]{10}$")
        @NotBlank
        @Sanitize
        String phone,

        @NotEmpty
        List<Specialty> specialty,

        @NotNull
        LocalDate laborStart,

        LocalDate laborEnd,

        @Positive
        @Min(5)
        @Max(240)
        int appointmentInterval,

        @Valid
        List<CreateScheduleRequest> schedules,

        @Email
        @Size(max = 120)
        @Sanitize
        String email,

        @Size(min = 8, max = 100)
        @NotBlank
        String password
) {}