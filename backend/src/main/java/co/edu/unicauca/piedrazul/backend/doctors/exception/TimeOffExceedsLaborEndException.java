package co.edu.unicauca.piedrazul.backend.doctors.exception;

import org.springframework.http.HttpStatus;

public class TimeOffExceedsLaborEndException extends DoctorBusinessException {
    public TimeOffExceedsLaborEndException(String message) {
        super(message, "TIME_OFF_EXCEEDS_LABOR_END", HttpStatus.CONFLICT);
    }
}
