package co.edu.unicauca.piedrazul.backend.user.exception;

import org.springframework.http.HttpStatus;

import java.util.UUID;

public class UserNotFoundException extends UserException {

    public UserNotFoundException(String message) {
        super(message);
    }
}