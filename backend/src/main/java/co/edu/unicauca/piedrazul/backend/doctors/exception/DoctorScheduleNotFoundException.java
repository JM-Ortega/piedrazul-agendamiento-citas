package co.edu.unicauca.piedrazul.backend.doctors.exception;

import org.springframework.http.HttpStatus;

public class DoctorScheduleNotFoundException extends DoctorBusinessException {
    public DoctorScheduleNotFoundException(String message) {
        super(message, "DOCTOR_SCHEDULE_NOT_FOUND", HttpStatus.NOT_FOUND);
    }
}