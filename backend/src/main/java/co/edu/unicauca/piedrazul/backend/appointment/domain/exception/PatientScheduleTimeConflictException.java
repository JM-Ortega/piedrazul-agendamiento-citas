package co.edu.unicauca.piedrazul.backend.appointment.domain.exception;

public class PatientScheduleTimeConflictException extends RuntimeException {
    public PatientScheduleTimeConflictException(String message) {
        super(message);
    }
}