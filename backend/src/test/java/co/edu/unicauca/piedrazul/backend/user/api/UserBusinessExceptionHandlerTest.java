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

class UserBusinessExceptionHandlerTest {

    private UserExceptionHandler handler;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        handler = new UserExceptionHandler();
        request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/test");
    }

    @Test
    void handleUserNotFound_shouldReturnNotFound() {
        UUID id = UUID.randomUUID();
        UserNotFoundException ex = new UserNotFoundException("Usuario con id "+ id +" no encontrado");

        ProblemDetail result = handler.handleBusinessException(ex, request);

        assertEquals(HttpStatus.NOT_FOUND.value(), result.getStatus());
        assertEquals("Usuario no encontrado", result.getTitle());
        assertEquals(ex.getMessage(), result.getDetail());
    }

    @Test
    void handleInvalidUserData_shouldReturnBadRequest() {
        InvalidUserDataException ex = new InvalidUserDataException("datos inválidos");

        ProblemDetail result = handler.handleBusinessException(ex, request);

        assertEquals(HttpStatus.BAD_REQUEST.value(), result.getStatus());
        assertEquals("Datos de usuario inválidos", result.getTitle());
        assertEquals(ex.getMessage(), result.getDetail());
    }

    @Test
    void handleIdentityProvider_shouldReturnBadGateway() {
        IdentityProviderException ex = new IdentityProviderException("error externo");

        ProblemDetail result = handler.handleBusinessException(ex, request);

        assertEquals(HttpStatus.BAD_GATEWAY.value(), result.getStatus());
        assertEquals("Error del proveedor de identidad", result.getTitle());
        assertEquals(ex.getMessage(), result.getDetail());
    }

    @Test
    void handleUserException_shouldReturnBadRequest() {
        UserBusinessException ex = new UserBusinessException("error genérico", "GENERIC_ERROR", HttpStatus.INTERNAL_SERVER_ERROR) {
        };

        ProblemDetail result = handler.handleBusinessException(ex, request);

        assertEquals(HttpStatus.BAD_REQUEST.value(), result.getStatus());
        assertEquals("Error de usuario", result.getTitle());
        assertEquals(ex.getMessage(), result.getDetail());
    }
}