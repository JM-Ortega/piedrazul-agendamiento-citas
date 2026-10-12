package co.edu.unicauca.piedrazul.backend.appointment.domain.model.statistics;

import java.util.UUID;

public record DoctorDailyCount(UUID doctorId, long total) {
}
