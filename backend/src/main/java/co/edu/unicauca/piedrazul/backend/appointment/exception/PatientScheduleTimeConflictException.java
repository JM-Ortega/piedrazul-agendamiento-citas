package co.edu.unicauca.piedrazul.backend.appointment.exception;

import org.springframework.http.HttpStatus;

public class PatientScheduleTimeConflictException extends AppointmentBusinessException {
    public PatientScheduleTimeConflictException(String message) {
        super(message, "PATIENT_TIME_CONFLICT", HttpStatus.CONFLICT);
    }
}