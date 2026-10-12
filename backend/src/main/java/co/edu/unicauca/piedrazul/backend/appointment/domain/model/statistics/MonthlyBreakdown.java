package co.edu.unicauca.piedrazul.backend.appointment.domain.model.statistics;

import java.util.List;
import java.util.Map;

public record MonthlyBreakdown(List<String> keys, List<MonthlyRow> rows) {
    public record MonthlyRow(int month, Map<String, Integer> values) {}
}
