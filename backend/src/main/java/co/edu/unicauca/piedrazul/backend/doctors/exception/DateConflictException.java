package co.edu.unicauca.piedrazul.backend.doctors.exception;

public class DateConflictException extends RuntimeException {
    public DateConflictException(String message) {
        super(message);
    }
}