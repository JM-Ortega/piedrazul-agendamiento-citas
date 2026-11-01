package co.edu.unicauca.piedrazul.backend.doctors.api.dtos.input;

import co.edu.unicauca.piedrazul.backend.jackson.sanitization.Sanitize;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.UUID;

@Schema(description = "Datos para registrar un periodo de descanso de un doctor")
public record CreateTimeOffRequest(
        @Schema(description = "Identificador del doctor", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
        @NotNull(message = "El doctor es requerido")
        UUID doctorId,
        @Schema(description = "Primer día de descanso (inclusive). Debe ser posterior al último día de la ventana de agendamiento del doctor", example = "2026-12-01")
        @NotNull(message = "La fecha de inicio del descanso es requerida")
        LocalDate startDate,
        @Schema(description = "Último día de descanso (inclusive). No puede ser anterior al inicio ni posterior al fin de vinculación del doctor", example = "2026-12-10")
        @NotNull(message = "La fecha de fin del descanso es requerida")
        LocalDate endDate,
        @Schema(description = "Motivo del descanso (opcional)", example = "Semana de descanso", maxLength = 255, nullable = true)
        @Sanitize
        @Size(max = 255, message = "El motivo no puede superar los 255 caracteres")
        String reason
) {}
