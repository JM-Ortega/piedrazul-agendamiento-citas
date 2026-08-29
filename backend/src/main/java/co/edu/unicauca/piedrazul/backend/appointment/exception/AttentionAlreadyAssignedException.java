package co.edu.unicauca.piedrazul.backend.appointment.exception;

import org.springframework.http.HttpStatus;

public class AttentionAlreadyAssignedException extends AppointmentBusinessException {
    public AttentionAlreadyAssignedException(String message) {
        super(message, "ATTENTION_ALREADY_ASSIGNED", HttpStatus.CONFLICT);
    }
}
