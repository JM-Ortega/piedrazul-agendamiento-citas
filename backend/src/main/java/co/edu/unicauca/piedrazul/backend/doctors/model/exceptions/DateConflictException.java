package co.edu.unicauca.piedrazul.backend.doctors.model.exceptions;

public class DateConflictException extends RuntimeException {
    public DateConflictException(String message) {
        super(message);
    }
}