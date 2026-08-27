package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api;

import co.edu.unicauca.piedrazul.backend.shared.BaseExceptionHandler;
import co.edu.unicauca.piedrazul.backend.shared.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = AppointmentController.class)
@Slf4j
public class AppointmentGlobalExceptionHandler extends BaseExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(
            MethodArgumentNotValidException ex,
            HttpServletRequest request
    ) {
        String detail = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .findFirst()
                .orElse("Validation error");

        log.warn("Erro de validación: {}", detail);
        return buildProblem(
                HttpStatus.BAD_REQUEST,
                "Validation error",
                detail,
                "appointment",
                "VALIDATION_ERROR",
                request
        );
    }

    @ExceptionHandler(RuntimeException.class)
    public ProblemDetail handleBusiness(
            RuntimeException ex,
            HttpServletRequest request
    ) {
        if (ex instanceof AuthorizationDeniedException authorizationDeniedException) {
            throw authorizationDeniedException;
        }

        if (ex instanceof AccessDeniedException accessDeniedException) {
            throw accessDeniedException;
        }

        String errorCode = (ex instanceof BusinessException be) ? be.getErrorCode() : "INTERNAL_ERROR";
        HttpStatus status = (ex instanceof BusinessException be) ? be.getStatus() : HttpStatus.INTERNAL_SERVER_ERROR;
        String module = (ex instanceof BusinessException be) ? be.getModule() : "appointment";

        log.warn("Error de negocio en el modulo appointment [{}]: {}", ex.getClass().getSimpleName(), ex.getMessage());
        return buildProblem(
                status,
                status.getReasonPhrase(),
                ex.getMessage(),
                module,
                errorCode,
                request
        );
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(
            IllegalArgumentException ex,
            HttpServletRequest request
    ) {
        log.warn("Mal envio de datos en el modulo appointment [{}]: {}", ex.getClass().getSimpleName(), ex.getMessage());
        return buildProblem(
                HttpStatus.BAD_REQUEST,
                "Bad request",
                ex.getMessage(),
                "appointment",
                "INVALID_ARGUMENT",
                request
        );
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(
            Exception ex,
            HttpServletRequest request
    ) {
        if (ex instanceof AuthorizationDeniedException authorizationDeniedException) {
            throw authorizationDeniedException;
        }

        if (ex instanceof AccessDeniedException accessDeniedException) {
            throw accessDeniedException;
        }

        log.error("Excepción no controlada en el módulo appointment", ex);
        return buildProblem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal server error",
                "Internal server error",
                "appointment",
                "INTERNAL_SERVER_ERROR",
                request
        );
    }
}