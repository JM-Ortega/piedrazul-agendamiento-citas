package co.edu.unicauca.piedrazul.backend.doctors.api.dtos.input;

import co.edu.unicauca.piedrazul.backend.jackson.sanitization.Sanitize;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.UUID;

public record CreateTimeOffRequest(
        @NotNull(message = "El doctor es requerido")
        UUID doctorId,
        @NotNull(message = "La fecha de inicio del descanso es requerida")
        LocalDate startDate,
        @NotNull(message = "La fecha de fin del descanso es requerida")
        LocalDate endDate,
        @Sanitize
        @Size(max = 255, message = "El motivo no puede superar los 255 caracteres")
        String reason
) {}
