package co.edu.unicauca.piedrazul.backend.statistics.dtos.output;

import java.util.Map;

/** month: 0 = enero ... 11 = diciembre. values: conteo por cada serie, en el mismo orden que series. */
public record MonthlyValuesDto(int month, Map<String, Integer> values) {}
