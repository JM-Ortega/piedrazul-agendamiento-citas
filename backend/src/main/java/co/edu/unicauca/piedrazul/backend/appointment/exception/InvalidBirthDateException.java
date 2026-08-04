package co.edu.unicauca.piedrazul.backend.appointment.exception;

import org.springframework.http.HttpStatus;

public class InvalidBirthDateException extends AppointmentBusinessException {
    public InvalidBirthDateException(String message) {
        super(message, "INVALID_BIRTH_DATE", HttpStatus.BAD_REQUEST);
    }
}
