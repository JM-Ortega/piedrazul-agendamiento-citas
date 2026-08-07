package co.edu.unicauca.piedrazul.backend.config.security;

import co.edu.unicauca.piedrazul.backend.shared.BaseExceptionHandler;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class SecurityExceptionHandler extends BaseExceptionHandler {

    @ExceptionHandler(AuthorizationDeniedException.class)
    public ProblemDetail handleAuthorizationDenied(
            AuthorizationDeniedException ex,
            HttpServletRequest request
    ) {
        return buildProblem(
                HttpStatus.FORBIDDEN,
                "Prohibido",
                "No tiene permisos para acceder a este recurso.",
                "security",
                "ACCESS_DENIED",
                request
        );
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(
            AccessDeniedException ex,
            HttpServletRequest request
    ) {
        return buildProblem(
                HttpStatus.FORBIDDEN,
                "Prohibido",
                "No tiene permisos para acceder a este recurso.",
                "security",
                "ACCESS_DENIED",
                request
        );
    }
}