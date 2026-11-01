package co.edu.unicauca.piedrazul.backend.doctors.exception;

import org.springframework.http.HttpStatus;

public class InactiveDoctorTimeOffException extends DoctorBusinessException {
    public InactiveDoctorTimeOffException(String message) {
        super(message, "TIME_OFF_INACTIVE_DOCTOR", HttpStatus.CONFLICT);
    }
}
