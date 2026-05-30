package co.edu.unicauca.piedrazul.backend.user.api;

import co.edu.unicauca.piedrazul.backend.user.exception.*;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserExceptionHandlerTest {

    private UserExceptionHandler handler;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        handler = new UserExceptionHandler();
        request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/test");
    }

    @Test
    void handleUserAlreadyExists_shouldReturnConflict() {
        UserAlreadyExistsException ex = new UserAlreadyExistsException("juan");

        ProblemDetail result = handler.handleUserAlreadyExists(ex, request);

        assertEquals(HttpStatus.CONFLICT.value(), result.getStatus());
        assertEquals("Usuario ya existe", result.getTitle());
        assertEquals(ex.getMessage(), result.getDetail());
    }

    @Test
    void handleUserNotFound_shouldReturnNotFound() {
        UUID id = UUID.randomUUID();
        UserNotFoundException ex = new UserNotFoundException("Usuario con id "+ id +" no encontrado");

        ProblemDetail result = handler.handleUserNotFound(ex, request);

        assertEquals(HttpStatus.NOT_FOUND.value(), result.getStatus());
        assertEquals("Usuario no encontrado", result.getTitle());
        assertEquals(ex.getMessage(), result.getDetail());
    }

    @Test
    void handleInvalidUserData_shouldReturnBadRequest() {
        InvalidUserDataException ex = new InvalidUserDataException("datos inválidos");

        ProblemDetail result = handler.handleInvalidUserData(ex, request);

        assertEquals(HttpStatus.BAD_REQUEST.value(), result.getStatus());
        assertEquals("Datos de usuario inválidos", result.getTitle());
        assertEquals(ex.getMessage(), result.getDetail());
    }

    @Test
    void handleIdentityProvider_shouldReturnBadGateway() {
        IdentityProviderException ex = new IdentityProviderException("error externo");

        ProblemDetail result = handler.handleIdentityProvider(ex, request);

        assertEquals(HttpStatus.BAD_GATEWAY.value(), result.getStatus());
        assertEquals("Error del proveedor de identidad", result.getTitle());
        assertEquals(ex.getMessage(), result.getDetail());
    }

    @Test
    void handleUserException_shouldReturnBadRequest() {
        UserException ex = new UserException("error genérico") {
        };

        ProblemDetail result = handler.handleUserException(ex, request);

        assertEquals(HttpStatus.BAD_REQUEST.value(), result.getStatus());
        assertEquals("Error de usuario", result.getTitle());
        assertEquals(ex.getMessage(), result.getDetail());
    }
}