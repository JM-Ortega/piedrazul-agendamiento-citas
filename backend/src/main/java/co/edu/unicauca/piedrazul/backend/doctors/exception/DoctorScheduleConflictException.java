package co.edu.unicauca.piedrazul.backend.doctors.exception;

import org.springframework.http.HttpStatus;

public class DoctorScheduleConflictException extends DoctorBusinessException {
    public DoctorScheduleConflictException(String message) {
        super(message, "DOCTOR_SCHEDULE_CONFLICT", HttpStatus.CONFLICT);
    }
}