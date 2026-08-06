package co.edu.unicauca.piedrazul.backend.appointment.domain.port.input;

import java.util.UUID;

public interface UpdateAppointmentStatusUseCase {
    void markAsAttended(UUID appointmentId, String clinicalHistoryDescription);
    void markAsUnassisted(UUID appointmentId);
}
