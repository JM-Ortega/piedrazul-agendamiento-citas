package co.edu.unicauca.piedrazul.backend.appointment.exception;

import org.springframework.http.HttpStatus;

public class InvalidEmailException extends AppointmentBusinessException {
    public InvalidEmailException(String message) {
        super(message, "INVALID_EMAIL", HttpStatus.BAD_REQUEST);
    }
}
