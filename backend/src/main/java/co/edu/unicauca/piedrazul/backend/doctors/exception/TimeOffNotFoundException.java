package co.edu.unicauca.piedrazul.backend.doctors.exception;

import org.springframework.http.HttpStatus;

public class TimeOffNotFoundException extends DoctorBusinessException {
    public TimeOffNotFoundException(String message) {
        super(message, "TIME_OFF_NOT_FOUND", HttpStatus.NOT_FOUND);
    }
}
