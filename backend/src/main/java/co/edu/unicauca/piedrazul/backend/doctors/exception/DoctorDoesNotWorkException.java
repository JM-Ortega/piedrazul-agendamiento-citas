package co.edu.unicauca.piedrazul.backend.doctors.exception;

import org.springframework.http.HttpStatus;

public class DoctorDoesNotWorkException extends DoctorBusinessException {
    public DoctorDoesNotWorkException(String message) {
        super(message, "NOT_WORKDAY", HttpStatus.BAD_REQUEST);
    }
}
