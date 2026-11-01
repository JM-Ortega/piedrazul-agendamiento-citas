package co.edu.unicauca.piedrazul.backend.doctors.exception;

import org.springframework.http.HttpStatus;

public class NoAvailableDoctorsException extends DoctorBusinessException {
    public NoAvailableDoctorsException(String message) {
        super(message, "NO_ACTIVE_DOCTORS", HttpStatus.NOT_FOUND);
    }
}
