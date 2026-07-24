package co.edu.unicauca.piedrazul.backend.doctors.api.dtos.internal;

import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.input.CreateScheduleRequest;
import co.edu.unicauca.piedrazul.backend.shared.enums.IdentificationType;
import co.edu.unicauca.piedrazul.backend.shared.enums.SpecialtyCode;
import co.edu.unicauca.piedrazul.backend.jackson.sanitization.Sanitize;
// import co.edu.unicauca.piedrazul.backend.jackson.validation.ValidDocument;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.time.LocalDate;
import java.util.List;

public record CreateDoctorRequest(
     @NotEmpty
        List<SpecialtyCode> specialty,

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