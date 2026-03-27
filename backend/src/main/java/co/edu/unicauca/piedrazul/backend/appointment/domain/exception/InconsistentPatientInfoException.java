package co.edu.unicauca.piedrazul.backend.appointment.domain.exception;

import org.springframework.http.HttpStatus;

public class InconsistentPatientInfoException extends AppointmentBusinessException {
    public InconsistentPatientInfoException(String message) {
        super(message, "INCONSISTENT_PATIENT_INFO", HttpStatus.BAD_REQUEST);
    }
}
