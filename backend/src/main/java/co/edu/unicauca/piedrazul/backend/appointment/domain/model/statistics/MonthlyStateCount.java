package co.edu.unicauca.piedrazul.backend.appointment.domain.model.statistics;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentState;

public record MonthlyStateCount(int month, AppointmentState state, long total) {
}
