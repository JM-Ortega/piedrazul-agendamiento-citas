package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output;

import java.util.UUID;

/** total: citas del doctor en la fecha consultada, sin contar las CANCELADA. */
public record DoctorDailyCountDto(UUID doctorId, int total) {}
