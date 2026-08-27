package co.edu.unicauca.piedrazul.backend.user.exception;

public abstract class UserException extends RuntimeException {

    public UserException(String message) {
        super(message);
    }
}