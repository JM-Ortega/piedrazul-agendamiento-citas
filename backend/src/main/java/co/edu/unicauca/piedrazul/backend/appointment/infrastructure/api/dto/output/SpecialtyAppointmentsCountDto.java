package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output;

import co.edu.unicauca.piedrazul.backend.shared.enums.SpecialtyCode;

import java.util.List;

public record SpecialtyAppointmentsCountDto(List<SpecialtyCode> specialtys, List<MonthlySpecialtyRowDto> rows) {}
