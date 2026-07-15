package co.edu.unicauca.piedrazul.backend.user.exception;

import java.util.UUID;

public class PersonNotFoundException extends UserException {

    public PersonNotFoundException(UUID id) {
        super("No se encontró una persona con id: " + id);
    }
}
