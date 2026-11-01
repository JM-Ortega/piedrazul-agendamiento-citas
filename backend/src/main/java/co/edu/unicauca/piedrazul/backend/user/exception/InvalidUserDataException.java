package co.edu.unicauca.piedrazul.backend.user.exception;

import org.springframework.http.HttpStatus;

public class InvalidUserDataException extends UserBusinessException {

    public InvalidUserDataException(String message) {
        super(message, "INVALID_USER_DATA", HttpStatus.BAD_REQUEST);
    }
}