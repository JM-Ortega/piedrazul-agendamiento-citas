package co.edu.unicauca.piedrazul.backend.doctors.exception;

import org.springframework.http.HttpStatus;

public class TimeOffOverlapException extends DoctorBusinessException {
    public TimeOffOverlapException(String message) {
        super(message, "TIME_OFF_OVERLAP", HttpStatus.CONFLICT);
    }
}
