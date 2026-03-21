package co.edu.unicauca.piedrazul.backend.appointment.domain.exception;

public class InvalidPatientInfoException extends RuntimeException {
    public InvalidPatientInfoException(String message) {
        super(message);
    }
}
