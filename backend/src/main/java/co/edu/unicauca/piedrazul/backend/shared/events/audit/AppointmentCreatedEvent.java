package co.edu.unicauca.piedrazul.backend.shared.events.audit;

import java.util.UUID;

public record AppointmentCreatedEvent(
        UUID appointmentId,
        UUID performedBy
) {
}
