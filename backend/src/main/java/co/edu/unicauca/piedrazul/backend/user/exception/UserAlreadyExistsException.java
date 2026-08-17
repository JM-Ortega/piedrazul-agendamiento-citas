package co.edu.unicauca.piedrazul.backend.user.exception;

import org.springframework.http.HttpStatus;

public class UserAlreadyExistsException extends UserBusinessException {

    public UserAlreadyExistsException() {
        super(
                "Ya existe una cuenta de usuario con esa identificación",
                "USER_ALREADY_EXISTS",
                HttpStatus.CONFLICT
        );
    }
}
