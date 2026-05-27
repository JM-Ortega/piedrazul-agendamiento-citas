package co.edu.unicauca.piedrazul.backend.report.dtos;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record SchedulerRequestDto(

        @NotNull(message = "La fecha es obligatoria")
        LocalDate date,

        @NotNull(message = "El formato de exportacion es obligatorio")
        ExportFormat format

) {}
