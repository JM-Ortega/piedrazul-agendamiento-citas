package co.edu.unicauca.piedrazul.backend.appointment.domain.exception;

import org.springframework.http.HttpStatus;

public class InvalidDocumentException extends AppointmentBusinessException {
    public InvalidDocumentException(String message) {
        super(message, "INVALID_DOCUMENT", HttpStatus.BAD_REQUEST);
    }
}
