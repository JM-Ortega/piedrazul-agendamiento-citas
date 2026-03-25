package co.edu.unicauca.piedrazul.backend.appointment.domain.exception;

public class PatientAlreadyScheduledInSpecialtyException extends RuntimeException {
    public PatientAlreadyScheduledInSpecialtyException(String message) {
        super(message);
    }
}