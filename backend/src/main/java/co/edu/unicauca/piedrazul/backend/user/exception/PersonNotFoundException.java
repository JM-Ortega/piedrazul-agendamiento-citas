package co.edu.unicauca.piedrazul.backend.user.exception;

import org.springframework.http.HttpStatus;

public class PersonNotFoundException extends UserBusinessException {
    public PersonNotFoundException(String message) {
        super(message, "PERSON_NOT_FOUND", HttpStatus.NOT_FOUND);
    }
}
