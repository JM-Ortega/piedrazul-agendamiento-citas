package co.edu.unicauca.piedrazul.backend.user.exception;

public class UserAlreadyExistsException extends UserException {

    public UserAlreadyExistsException(String documentId) {
        super("El usuario con documento '" + documentId + "' ya existe");
    }
}