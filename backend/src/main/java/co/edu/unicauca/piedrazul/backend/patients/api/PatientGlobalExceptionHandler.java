package co.edu.unicauca.piedrazul.backend.patients.api;

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

@RestControllerAdvice(basePackageClasses = PatientController.class)
@Slf4j
public class PatientGlobalExceptionHandler extends BaseExceptionHandler {

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

        log.warn("Error de validacion en patients: {}", detail);
        return buildProblem(
                HttpStatus.BAD_REQUEST,
                "Error de validación",
                detail,
                "patients",
                "VALIDATION_ERROR",
                request
        );
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(
            IllegalArgumentException ex,
            HttpServletRequest request
    ) {
        log.warn("Argumento invalido en patients: {}", ex.getMessage());
        return buildProblem(
                HttpStatus.BAD_REQUEST,
                "Solicitud inválida",
                ex.getMessage(),
                "patients",
                "INVALID_ARGUMENT",
                request
        );
    }

    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail handleIllegalState(
            IllegalStateException ex,
            HttpServletRequest request
    ) {
        log.warn("Estado invalido en patients: {}", ex.getMessage());
        return buildProblem(
                HttpStatus.CONFLICT,
                "Conflicto",
                ex.getMessage(),
                "patients",
                "INVALID_STATE",
                request
        );
    }

    @ExceptionHandler(RuntimeException.class)
    public ProblemDetail handleRuntime(
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
        String module = (ex instanceof BusinessException be) ? be.getModule() : "patients";

        if (status.is5xxServerError()) {
            log.error("Error no controlado en patients", ex);
        } else {
            log.warn("Error de negocio en patients [{}]: {}", ex.getClass().getSimpleName(), ex.getMessage());
        }

        return buildProblem(
                status,
                spanishTitle(status),
                ex.getMessage(),
                module,
                errorCode,
                request
        );
    }
}