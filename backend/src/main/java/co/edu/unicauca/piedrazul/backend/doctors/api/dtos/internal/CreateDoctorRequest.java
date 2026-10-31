package co.edu.unicauca.piedrazul.backend.doctors.api.dtos.internal;

import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.input.ScheduleRequest;
import co.edu.unicauca.piedrazul.backend.shared.enums.SpecialtyCode;
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
        Integer appointmentInterval,

        @Positive
        @Min(1)
        Integer bookingWindowWeeks,

        @Valid
        List<ScheduleRequest> schedules
) {}