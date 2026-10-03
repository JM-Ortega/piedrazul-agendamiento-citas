package co.edu.unicauca.piedrazul.backend.doctors.api.dtos.output;

import co.edu.unicauca.piedrazul.backend.doctors.domain.DoctorTimeOff;
import co.edu.unicauca.piedrazul.backend.doctors.domain.TimeOffStatus;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.UUID;

@Schema(description = "Periodo de descanso de un doctor")
public record TimeOffResponse(
        @Schema(description = "Identificador del descanso") UUID id,
        @Schema(description = "Identificador del doctor") UUID doctorId,
        @Schema(description = "Primer día de descanso (inclusive)", example = "2026-12-01") LocalDate startDate,
        @Schema(description = "Último día de descanso (inclusive). Si se recortó, es el último día que sí descansó", example = "2026-12-10") LocalDate endDate,
        @Schema(description = "Motivo del descanso, si se indicó", nullable = true) String reason,
        @Schema(description = "Estado calculado según la fecha de hoy") TimeOffStatus status
) {
    public static TimeOffResponse fromEntity(DoctorTimeOff timeOff, LocalDate today) {
        return new TimeOffResponse(
                timeOff.getId(),
                timeOff.getDoctorId(),
                timeOff.getStartDate(),
                timeOff.getEndDate(),
                timeOff.getReason(),
                timeOff.statusOn(today)
        );
    }
}
