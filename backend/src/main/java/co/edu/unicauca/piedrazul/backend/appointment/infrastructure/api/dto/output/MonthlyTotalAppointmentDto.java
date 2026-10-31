package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output;

/** month: 0 = enero ... 11 = diciembre */
public record MonthlyTotalAppointmentDto(int month, int total) {}
