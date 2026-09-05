package co.edu.unicauca.piedrazul.backend.appointment.domain.port.input;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentTime;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output.AvailableDateSlots;

import java.util.List;
import java.util.UUID;

public interface GetAvailableDatesAndSlotsUseCase {
    // Obtener franjas disponibles para mostrarle al frontend antes de agendar
    List<AvailableDateSlots> getAvailableDatesAndSlots(UUID idDoctor);
}
