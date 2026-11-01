package co.edu.unicauca.piedrazul.backend.appointment.domain.model.statistics;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentState;
import java.util.Map;

public record MonthlyStateCounts(int month, Map<AppointmentState, Integer> counts) {}
