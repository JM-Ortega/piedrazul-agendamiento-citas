package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Specialty;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record SpecialtyDoctorResponse(
        Specialty specialty,
        UUID doctorId,
        String doctorName,
        LocalDate fechaFinalTrabajo,
        List<Integer> workDays
) {
}
