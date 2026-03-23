package co.edu.unicauca.piedrazul.backend.appointment.domain.exception;

public class InvalidBirthDateException extends RuntimeException {
    public InvalidBirthDateException(String message) {
        super(message);
    }
}
