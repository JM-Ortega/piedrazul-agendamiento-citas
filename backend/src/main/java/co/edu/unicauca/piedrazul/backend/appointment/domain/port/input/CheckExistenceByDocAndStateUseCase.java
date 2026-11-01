package co.edu.unicauca.piedrazul.backend.appointment.domain.port.input;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentState;
import java.util.UUID;

public interface CheckExistenceByDocAndStateUseCase {
    boolean execute(UUID idDoctor, AppointmentState state);
}
