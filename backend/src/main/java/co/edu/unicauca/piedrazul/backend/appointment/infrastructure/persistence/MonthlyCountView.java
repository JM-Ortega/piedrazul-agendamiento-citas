package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.persistence;

public interface MonthlyCountView {
    Integer getMonthNumber();
    Object getGroupKey();
    Long getTotal();
}
