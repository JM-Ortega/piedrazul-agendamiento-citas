package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output;

/**
 * month: 0 = enero ... 11 = diciembre.
 * totalAppointments: citas del mes en todos los estados.
 * count: citas en el estado solicitado; si no se solicitó uno, CANCELADA + NO_ASISTIO.
 */
public record MonthlyCancellationDataDto(int month, int totalAppointments, int count) {}
