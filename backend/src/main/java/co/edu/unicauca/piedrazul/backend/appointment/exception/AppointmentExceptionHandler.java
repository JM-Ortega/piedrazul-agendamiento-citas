package co.edu.unicauca.piedrazul.backend.appointment.exception;

import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.AppointmentController;
import co.edu.unicauca.piedrazul.backend.shared.BaseExceptionHandler;
import co.edu.unicauca.piedrazul.backend.shared.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = AppointmentController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class AppointmentExceptionHandler extends BaseExceptionHandler {

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
                .orElse("Error de validación");

        log.warn("Erro de validación: {}", detail);
        return buildProblem(
                HttpStatus.BAD_REQUEST,
                "Error de validación",
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

        if (!(ex instanceof BusinessException be)) {
            return buildUnexpectedProblem(log, ex, "appointment", request);
        }

        log.warn("Error de negocio en el modulo appointment [{}]: {}", ex.getClass().getSimpleName(), ex.getMessage());
        return buildProblem(
                be.getStatus(),
                spanishTitle(be.getStatus()),
                ex.getMessage(),
                be.getModule(),
                be.getErrorCode(),
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
                "Solicitud inválida",
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

        logUnexpected(log, "Excepción no controlada en el módulo appointment", ex, request);
        return buildProblem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Error interno del servidor",
                "Error interno del servidor",
                "appointment",
                "INTERNAL_SERVER_ERROR",
                request
        );
    }
}