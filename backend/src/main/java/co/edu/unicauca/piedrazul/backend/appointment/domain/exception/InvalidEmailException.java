package co.edu.unicauca.piedrazul.backend.appointment.domain.exception;

public class InvalidEmailException extends RuntimeException {
    public InvalidEmailException(String message) {
        super(message);
    }
}
