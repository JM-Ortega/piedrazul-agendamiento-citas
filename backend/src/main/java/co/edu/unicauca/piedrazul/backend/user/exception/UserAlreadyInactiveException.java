package co.edu.unicauca.piedrazul.backend.user.exception;

import java.util.UUID;

public class UserAlreadyInactiveException extends UserException {

    public UserAlreadyInactiveException(UUID id) {
        super("El usuario ya está inactivo: " + id);
    }
}