package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output;

/** month: 0 = enero ... 11 = diciembre */
public record MonthlyCancellationDataDto(int month, int totalAppointments, int cancelledCount) {}
