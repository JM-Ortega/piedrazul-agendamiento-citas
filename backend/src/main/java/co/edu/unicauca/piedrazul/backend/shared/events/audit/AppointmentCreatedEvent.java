package co.edu.unicauca.piedrazul.backend.shared.events.audit;

public record AppointmentCreatedEvent(
        String appointmentId,
        String performedBy
) {
}
