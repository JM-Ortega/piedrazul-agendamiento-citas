package co.edu.unicauca.piedrazul.backend.user.exception;

import java.util.UUID;

public class PersonAlreadyLinkedUserException extends UserException {

    private PersonAlreadyLinkedUserException(String message) {
        super(message);
    }

    public static PersonAlreadyLinkedUserException forPerson(UUID personId) {
        return new PersonAlreadyLinkedUserException(
                "La persona con id " + personId + " ya tiene una cuenta de usuario vinculada"
        );
    }

    public static PersonAlreadyLinkedUserException forUserId(UUID userId) {
        return new PersonAlreadyLinkedUserException(
                "La cuenta de usuario " + userId + " ya está vinculada a otra persona"
        );
    }
}
