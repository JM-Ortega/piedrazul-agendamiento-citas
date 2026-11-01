package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.persistence;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentState;

public interface MonthlyStateCountView {
    Integer getMonthNumber();
    AppointmentState getState();
    Long getTotal();
}
