package co.edu.unicauca.piedrazul.backend.doctors.controller.dtos.input;

import co.edu.unicauca.piedrazul.backend.doctors.model.models.Workday;
import jakarta.validation.constraints.NotNull;

import java.time.LocalTime;

public record CreateScheduleRequest(
        @NotNull LocalTime startTime,
        @NotNull LocalTime endTime,
        @NotNull Workday workday
) {}

