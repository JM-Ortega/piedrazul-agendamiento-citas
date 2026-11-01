package co.edu.unicauca.piedrazul.backend.statistics.dtos.output;

/** month: 0 = enero ... 11 = diciembre. rate: porcentaje entero de 0 a 100. */
public record MonthlyRateDto(int month, int rate, int count) {}
