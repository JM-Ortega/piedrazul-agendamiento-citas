package co.edu.unicauca.piedrazul.backend.appointment.domain.port.input;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentTime;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface GetAvailableSlotsUseCase {
    // Obtener franjas disponibles para mostrarle al frontend antes de agendar
    List<AppointmentTime> getAvailableSlots(UUID idDoctor, LocalDate date);
}
