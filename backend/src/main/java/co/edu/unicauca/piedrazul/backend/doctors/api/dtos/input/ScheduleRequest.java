package co.edu.unicauca.piedrazul.backend.doctors.api.dtos.input;

import co.edu.unicauca.piedrazul.backend.doctors.domain.Workday;
import jakarta.validation.constraints.NotNull;

import java.time.LocalTime;

public record ScheduleRequest(
        @NotNull(message = "El horario debe tener una hora inicial")
        LocalTime startTime,
        @NotNull(message = "El horario debe tener una hora final")
        LocalTime endTime,
        @NotNull(message = "El horario debe tener un dia asignado")
        Workday workday
) {}

