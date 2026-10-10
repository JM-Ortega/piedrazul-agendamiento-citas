package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output;

import co.edu.unicauca.piedrazul.backend.shared.enums.SpecialtyCode;

import java.util.Map;

/** month: 0 = enero ... 11 = diciembre */
public record MonthlySpecialtyRowDto(int month, Map<SpecialtyCode, Integer> values) {}
