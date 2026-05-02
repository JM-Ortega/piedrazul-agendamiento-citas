package co.edu.unicauca.piedrazul.backend.doctors.api.dtos.input;

import co.edu.unicauca.piedrazul.backend.config.security.sanitization.Sanitize;
import co.edu.unicauca.piedrazul.backend.config.security.validation.ValidDocument;
import co.edu.unicauca.piedrazul.backend.doctors.domain.DocumentType;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Specialty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

@ValidDocument(documentField = "identification", typeField = "documentType")
public record CreateDoctorRequest(
        @Size(min = 2, max = 60)
        @Pattern(regexp = "^[\\p{L} '-]{2,60}$")
        @NotBlank @Sanitize
        String firstName,

        @Size(min = 2, max = 60)
        @Pattern(regexp = "^[\\p{L} '-]{2,60}$")
        @NotBlank @Sanitize
        String lastName,

        @NotNull
        DocumentType documentType,

        @Size(max = 20)
        @NotBlank @Sanitize
        String identification,

        @Pattern(regexp = "^[0-9]{7,15}$")
        @NotBlank @Sanitize
        String phone,

        @NotNull
        List<Specialty> specialty,

        @NotNull
        LocalDate laborStart,

        LocalDate laborEnd,

        @Positive
        int appointmentInterval,
        List<CreateScheduleRequest> schedules,

        @Email
        @Size(max = 120)
        @Sanitize
        String email,

        @Size(min = 8, max = 100)
        @NotBlank
        String password
) {}