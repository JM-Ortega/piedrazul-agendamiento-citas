package co.edu.unicauca.piedrazul.backend.doctors.exception;

import org.springframework.http.HttpStatus;

public class TimeOffAlreadyEndedException extends DoctorBusinessException {
    public TimeOffAlreadyEndedException(String message) {
        super(message, "TIME_OFF_ALREADY_ENDED", HttpStatus.CONFLICT);
    }
}
