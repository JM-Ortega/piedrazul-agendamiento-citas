package co.edu.unicauca.piedrazul.backend.user.exception;

import java.util.UUID;

public class UserAlreadyActiveException extends UserException {

    public UserAlreadyActiveException(UUID id) {
        super("El usuario ya está activo: " + id);
    }
}