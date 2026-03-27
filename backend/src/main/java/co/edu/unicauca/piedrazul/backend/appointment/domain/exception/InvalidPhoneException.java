package co.edu.unicauca.piedrazul.backend.appointment.domain.exception;

import org.springframework.http.HttpStatus;

public class InvalidPhoneException extends AppointmentBusinessException {
    public InvalidPhoneException(String message) {
        super(message, "INVALID_PHONE", HttpStatus.BAD_REQUEST);
    }
}
