package co.edu.unicauca.piedrazul.backend.appointment.exception;

import org.springframework.http.HttpStatus;

public class CancelAppointmentNotAllowedException extends AppointmentBusinessException {
    public CancelAppointmentNotAllowedException(String message) {
        super(message, "CANCEL_NOT_ALOWED", HttpStatus.CONFLICT);
    }
}
