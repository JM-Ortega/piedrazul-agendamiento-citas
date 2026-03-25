package co.edu.unicauca.piedrazul.backend.doctors.api;

import co.edu.unicauca.piedrazul.backend.shared.BusinessException;
import co.edu.unicauca.piedrazul.backend.shared.BaseExceptionHandler;
import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = {DoctorController.class, SheduleController.class})
@Slf4j
public class DoctorGlobalExceptionHandler extends BaseExceptionHandler {

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

        log.warn("Error de validacion en doctors: {}", detail);
        return buildProblem(
                HttpStatus.BAD_REQUEST,
                "Validation error",
                detail,
                "doctors",
                "VALIDATION_ERROR",
                request
        );
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public ProblemDetail handleNotFound(
            EntityNotFoundException ex,
            HttpServletRequest request
    ) {
        log.warn("Recurso no encontrado en doctors: {}", ex.getMessage());
        return buildProblem(
                HttpStatus.NOT_FOUND,
                "Not found",
                ex.getMessage(),
                "doctors",
                "DOCTOR_NOT_FOUND",
                request
        );
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(
            IllegalArgumentException ex,
            HttpServletRequest request
    ) {
        log.warn("Argumento invalido en doctors: {}", ex.getMessage());
        return buildProblem(
                HttpStatus.BAD_REQUEST,
                "Bad request",
                ex.getMessage(),
                "doctors",
                "INVALID_ARGUMENT",
                request
        );
    }

    @ExceptionHandler(RuntimeException.class)
    public ProblemDetail handleRuntime(
            RuntimeException ex,
            HttpServletRequest request
    ) {
        String errorCode = (ex instanceof BusinessException be) ? be.getErrorCode() : "INTERNAL_ERROR";
        HttpStatus status = (ex instanceof BusinessException be) ? be.getStatus() : HttpStatus.INTERNAL_SERVER_ERROR;
        String module = (ex instanceof BusinessException be) ? be.getModule() : "doctors";

        if (status.is5xxServerError()) {
            log.error("Error no controlado en doctors", ex);
        } else {
            log.warn("Error de negocio en doctors [{}]: {}", ex.getClass().getSimpleName(), ex.getMessage());
        }

        return buildProblem(
                status,
                status.getReasonPhrase(),
                ex.getMessage(),
                module,
                errorCode,
                request
        );
    }
}
