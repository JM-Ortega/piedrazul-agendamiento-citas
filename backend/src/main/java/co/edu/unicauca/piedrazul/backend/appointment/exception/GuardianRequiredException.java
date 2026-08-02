package co.edu.unicauca.piedrazul.backend.appointment.exception;

import org.springframework.http.HttpStatus;

public class GuardianRequiredException extends AppointmentBusinessException {
    public GuardianRequiredException(String message) {
        super(message, "GUARDIAN_REQUIRED", HttpStatus.BAD_REQUEST);
    }
}
