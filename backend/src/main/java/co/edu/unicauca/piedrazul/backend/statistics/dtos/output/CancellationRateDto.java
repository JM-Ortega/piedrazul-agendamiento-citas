package co.edu.unicauca.piedrazul.backend.statistics.dtos.output;

import java.util.List;

public record CancellationRateDto(List<MonthlyRateDto> months, int yearRate, int yearCount) {}
