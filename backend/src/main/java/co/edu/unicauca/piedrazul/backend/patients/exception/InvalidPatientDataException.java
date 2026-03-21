package co.edu.unicauca.piedrazul.backend.patients.exception;

public class InvalidPatientDataException extends RuntimeException {

    public InvalidPatientDataException(String message) {
        super(message);
    }
}