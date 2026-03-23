package co.edu.unicauca.piedrazul.backend.appointment.domain.exception;

public class InconsistentPatientInfoException extends RuntimeException {
    public InconsistentPatientInfoException(String message) {
        super(message);
    }
}
