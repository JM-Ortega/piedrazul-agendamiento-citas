package co.edu.unicauca.piedrazul.backend.doctors.api.dtos.internal;

import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.input.CreateScheduleRequest;
import co.edu.unicauca.piedrazul.backend.doctors.domain.SpecialtyCode;
import co.edu.unicauca.piedrazul.backend.jackson.sanitization.Sanitize;
// import co.edu.unicauca.piedrazul.backend.jackson.validation.ValidDocument;
import co.edu.unicauca.piedrazul.backend.doctors.domain.DocumentType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.time.LocalDate;
import java.util.List;

// @ValidDocument(documentField = "identification", typeField = "documentType")
public record CreateDoctorRequest(
        @NotNull
        DocumentType documentType,

        // Número Colombiano
        @Pattern(regexp = "^[0-9]{10}$")
        @NotBlank
        @Sanitize
        String phone,

        @NotEmpty
        List<String> specialty,

        @NotNull
        LocalDate laborStart,

        LocalDate laborEnd,

        @Positive
        @Min(5)
        @Max(240)
        int appointmentInterval,

        @Positive
        @Min(1)
        int bookingWindowWeeks,

        @Valid
        List<CreateScheduleRequest> schedules
) {}