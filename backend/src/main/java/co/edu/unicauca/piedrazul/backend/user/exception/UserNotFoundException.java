package co.edu.unicauca.piedrazul.backend.user.exception;

import org.springframework.http.HttpStatus;

public class UserNotFoundException extends UserBusinessException {
    public UserNotFoundException(String message) {
        super(message, "USER_NOT_FOUND", HttpStatus.BAD_GATEWAY);
    }
}