package co.edu.unicauca.piedrazul.backend.doctors.api.dtos.input;

import co.edu.unicauca.piedrazul.backend.doctors.domain.Workday;
import jakarta.validation.constraints.NotNull;

import java.time.LocalTime;

public record CreateScheduleRequest(
        @NotNull LocalTime startTime,
        @NotNull LocalTime endTime,
        @NotNull Workday workday
) {}

