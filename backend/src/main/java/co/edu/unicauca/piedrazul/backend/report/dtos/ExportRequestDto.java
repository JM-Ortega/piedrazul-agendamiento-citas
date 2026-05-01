package co.edu.unicauca.piedrazul.backend.report.dtos;

import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public record ExportRequestDto(
        @NotNull(message = "El id del medico es obligatorio")
        UUID idDoctor,

        @NotNull(message = "Debe elegir el formato de exportacion")
        ExportFormat format,

        @NotNull(message = "Debe seleccionar al menos una columna para exportar")
        List<ReportColumn> columns,

        //si se recibe null se exportan todas las citas sin filtrar
        AppointmentStateFilter state
) {}
