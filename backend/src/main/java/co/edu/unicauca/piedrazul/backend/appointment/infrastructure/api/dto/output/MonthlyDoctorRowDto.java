package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output;

import java.util.Map;
import java.util.UUID;

/** month: 0 = enero ... 11 = diciembre */
public record MonthlyDoctorRowDto(int month, Map<UUID, Integer> values) {}
