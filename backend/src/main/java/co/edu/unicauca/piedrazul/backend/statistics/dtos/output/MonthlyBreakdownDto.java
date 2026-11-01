package co.edu.unicauca.piedrazul.backend.statistics.dtos.output;

import java.util.List;

/** series: médicos o especialidades; rows: siempre los 12 meses. */
public record MonthlyBreakdownDto(List<String> series, List<MonthlyValuesDto> rows) {}
