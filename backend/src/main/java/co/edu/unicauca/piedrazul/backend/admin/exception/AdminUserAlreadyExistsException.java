package co.edu.unicauca.piedrazul.backend.admin.exception;

public class AdminUserAlreadyExistsException extends RuntimeException {

    public AdminUserAlreadyExistsException(String documentId) {
        super("Ya existe un usuario con documento de identidad: " + documentId);
    }
}