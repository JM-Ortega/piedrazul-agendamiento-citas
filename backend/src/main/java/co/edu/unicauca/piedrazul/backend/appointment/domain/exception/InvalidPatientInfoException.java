package co.edu.unicauca.piedrazul.backend.appointment.domain.exception;

import org.springframework.http.HttpStatus;

public class InvalidPatientInfoException extends AppointmentBusinessException {
    public InvalidPatientInfoException(String message) {
        super(message, "INVALID_PATIENT_INFO", HttpStatus.BAD_REQUEST);
    }
}
