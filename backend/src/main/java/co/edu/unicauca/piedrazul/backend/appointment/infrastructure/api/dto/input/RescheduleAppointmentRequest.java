package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.input;

import co.edu.unicauca.piedrazul.backend.shared.enums.SpecialtyCode;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * Datos de la cita nueva al reagendar. El paciente no viaja: se toma de la cita original.
 */
@Setter
@Getter
public class RescheduleAppointmentRequest {
    @NotNull(message = "El médico es obligatorio")
    private UUID doctorId;

    @NotNull(message = "La especialidad es obligatoria")
    private SpecialtyCode specialty;

    @NotNull(message = "La fecha es obligatoria")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate date;

    @NotNull(message = "La hora es obligatoria")
    private LocalTime startTime;
}
