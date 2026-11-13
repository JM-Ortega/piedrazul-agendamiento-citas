package co.edu.unicauca.piedrazul.backend.user.api;

import co.edu.unicauca.piedrazul.backend.user.exception.*;
import jakarta.servlet.http.HttpServletRequest;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
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
        assertEquals("No encontrado", result.getTitle());
        assertEquals("USER_NOT_FOUND", result.getProperties().get("errorCode"));
        assertEquals(ex.getMessage(), result.getDetail());
    }

    @Test
    void handleInvalidUserData_shouldReturnBadRequest() {
        InvalidUserDataException ex = new InvalidUserDataException("datos inválidos");

        ProblemDetail result = handler.handleBusinessException(ex, request);

        assertEquals(HttpStatus.BAD_REQUEST.value(), result.getStatus());
        assertEquals("Solicitud inválida", result.getTitle());
        assertEquals("INVALID_USER_DATA", result.getProperties().get("errorCode"));
        assertEquals(ex.getMessage(), result.getDetail());
    }

    @Test
    void handleIdentityProvider_shouldReturnBadGateway() {
        IdentityProviderException ex = new IdentityProviderException("error externo");

        ProblemDetail result = handler.handleBusinessException(ex, request);

        assertEquals(HttpStatus.BAD_GATEWAY.value(), result.getStatus());
        assertEquals("KEYCLOAK_EXCEPTION", result.getProperties().get("errorCode"));
        assertEquals(ex.getMessage(), result.getDetail());
    }

    @Test
    void handleUserException_shouldUseTheStatusOfTheException() {
        UserBusinessException ex = new UserBusinessException("error genérico", "GENERIC_ERROR", HttpStatus.INTERNAL_SERVER_ERROR) {
        };

        ProblemDetail result = handler.handleBusinessException(ex, request);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR.value(), result.getStatus());
        assertEquals("Error interno del servidor", result.getTitle());
        assertEquals("GENERIC_ERROR", result.getProperties().get("errorCode"));
        assertEquals(ex.getMessage(), result.getDetail());
    }

    @Test
    void aBusinessRejectionIsLoggedByItsCodeAndNotByItsMessageWhileTheResponseKeepsIt() {
        Logger handlerLogger = (Logger) LoggerFactory.getLogger(UserExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        handlerLogger.addAppender(appender);

        try {
            UserAlreadyExistsException ex = new UserAlreadyExistsException(
                    "Ya existe un usuario registrado (SYN-USUARIO / SYN-CORREO@example.test)");

            ProblemDetail result = handler.handleBusinessException(ex, request);

            // Contrato que consume el frontend: estado, código, título, detalle y módulo intactos.
            assertEquals(ex.getStatus().value(), result.getStatus());
            assertEquals(ex.getErrorCode(), result.getProperties().get("errorCode"));
            assertEquals("user", result.getProperties().get("module"));
            assertEquals(ex.getMessage(), result.getDetail());
            assertThat(appender.list).singleElement().satisfies(event ->
                    assertThat(event.getFormattedMessage())
                            .contains("UserAlreadyExistsException")
                            .contains(ex.getErrorCode())
                            .doesNotContain("SYN-USUARIO")
                            .doesNotContain("SYN-CORREO"));
        } finally {
            handlerLogger.detachAppender(appender);
        }
    }
}
