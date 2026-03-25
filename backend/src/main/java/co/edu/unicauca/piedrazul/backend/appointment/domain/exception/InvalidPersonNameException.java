package co.edu.unicauca.piedrazul.backend.appointment.domain.exception;

import org.springframework.http.HttpStatus;

public class InvalidPersonNameException extends AppointmentBusinessException {
    public InvalidPersonNameException(String message) {
        super(message, "INVALID_PERSON_NAME", HttpStatus.BAD_REQUEST);
    }
}
