package co.edu.unicauca.piedrazul.backend.doctors.api;

import co.edu.unicauca.piedrazul.backend.shared.BaseExceptionHandler;
import co.edu.unicauca.piedrazul.backend.doctors.exception.DoctorBusinessException;
import jakarta.persistence.EntityNotFoundException;
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

@RestControllerAdvice(basePackageClasses = {
        DoctorController.class,
        ScheduleController.class
})
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class DoctorExceptionHandler extends BaseExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(
            MethodArgumentNotValidException ex,
            HttpServletRequest request
    ) {

        log.warn("Error de validación en doctors");

        return buildValidationProblem(
                ex,
                request,
                "doctors"
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
                "No encontrado",
                ex.getMessage(),
                "doctors",
                "DOCTOR_MODULE_NOT_FOUND",
                request
        );
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(
            IllegalArgumentException ex,
            HttpServletRequest request
    ) {

        log.warn("Argumento inválido en doctors: {}", ex.getMessage());

        return buildProblem(
                HttpStatus.BAD_REQUEST,
                "Solicitud inválida",
                ex.getMessage(),
                "doctors",
                "INVALID_ARGUMENT",
                request
        );
    }

    @ExceptionHandler(DoctorBusinessException.class)
    public ProblemDetail handleBusinessException(
            DoctorBusinessException ex,
            HttpServletRequest request
    ) {

        log.warn(
                "Error de negocio en doctors [{}]: {}",
                ex.getClass().getSimpleName(),
                ex.getMessage()
        );

        return buildProblem(
                ex.getStatus(),
                spanishTitle(ex.getStatus()),
                ex.getMessage(),
                ex.getModule(),
                ex.getErrorCode(),
                request
        );
    }
}