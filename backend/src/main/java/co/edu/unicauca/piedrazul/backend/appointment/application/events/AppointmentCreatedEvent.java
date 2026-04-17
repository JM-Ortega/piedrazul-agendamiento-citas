package co.edu.unicauca.piedrazul.backend.appointment.application.events;

public record AppointmentCreatedEvent(
        String appointmentId,
        String performedBy
) {
}
