package co.edu.unicauca.piedrazul.backend.shared.events;

public record AppointmentCreatedEvent(
        String appointmentId,
        String performedBy
) {
}
