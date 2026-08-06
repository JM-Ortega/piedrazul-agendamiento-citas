package co.edu.unicauca.piedrazul.backend.user.api;


import co.edu.unicauca.piedrazul.backend.shared.BaseExceptionHandler;
import co.edu.unicauca.piedrazul.backend.user.exception.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class UserExceptionHandler extends BaseExceptionHandler {

    @ExceptionHandler(UserAlreadyExistsException.class)
    public ProblemDetail handleUserAlreadyExists(UserAlreadyExistsException ex, HttpServletRequest request) {
        return buildProblem(HttpStatus.CONFLICT, "Usuario ya existe", ex.getMessage(), "user", "USER_ALREADY_EXISTS", request);
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ProblemDetail handleUserNotFound(UserNotFoundException ex, HttpServletRequest request) {
        return buildProblem(HttpStatus.NOT_FOUND, "Usuario no encontrado", ex.getMessage(), "user", "USER_NOT_FOUND", request);
    }

    @ExceptionHandler(InvalidUserDataException.class)
    public ProblemDetail handleInvalidUserData(InvalidUserDataException ex, HttpServletRequest request) {
        return buildProblem(HttpStatus.BAD_REQUEST, "Datos de usuario inválidos", ex.getMessage(), "user", "INVALID_USER_DATA", request);
    }

    @ExceptionHandler(IdentityProviderException.class)
    public ProblemDetail handleIdentityProvider(IdentityProviderException ex, HttpServletRequest request) {
        return buildProblem(HttpStatus.BAD_GATEWAY, "Error del proveedor de identidad", ex.getMessage(), "user", "IDENTITY_PROVIDER_ERROR", request);
    }

    @ExceptionHandler(PersonAlreadyExistsException.class)
    public ProblemDetail handlePersonAlreadyExists(PersonAlreadyExistsException ex, HttpServletRequest request) {
        return buildProblem(HttpStatus.CONFLICT, "Persona ya existe", ex.getMessage(), "user", "PERSON_ALREADY_EXISTS", request);
    }

    @ExceptionHandler(PersonAlreadyLinkedUserException.class)
    public ProblemDetail handlePersonAlreadyLinkedUser(PersonAlreadyLinkedUserException ex, HttpServletRequest request) {
        return buildProblem(HttpStatus.CONFLICT, "Persona ya vinculada", ex.getMessage(), "user", "PERSON_ALREADY_LINKED_USER", request);
    }

    @ExceptionHandler(PersonNotFoundException.class)
    public ProblemDetail handlePersonNotFound(PersonNotFoundException ex, HttpServletRequest request) {
        return buildProblem(HttpStatus.NOT_FOUND, "Persona no encontrada", ex.getMessage(), "user", "PERSON_NOT_FOUND", request);
    }

    @ExceptionHandler(UserException.class)
    public ProblemDetail handleUserException(UserException ex, HttpServletRequest request) {
        return buildProblem(HttpStatus.BAD_REQUEST, "Error de usuario", ex.getMessage(), "user", "USER_ERROR", request);
    }
}