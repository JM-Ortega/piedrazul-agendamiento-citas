package co.edu.unicauca.piedrazul.backend.notifications.domain.port.output;

import java.util.Objects;
import java.util.UUID;

/**
 * Schedule reclamado por un trabajador, con el número de intentos que tenía su notificación al
 * reclamarlo. Ese número es la cerca de propiedad: si otro trabajador avanza el trabajo, deja de
 * coincidir y las escrituras del primero se rechazan.
 */
public record ClaimedSchedule(UUID scheduleId, int attemptCount) {

    public ClaimedSchedule {
        Objects.requireNonNull(scheduleId, "El schedule es obligatorio");
    }
}
