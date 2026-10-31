package co.edu.unicauca.piedrazul.backend.user.api;

import co.edu.unicauca.piedrazul.backend.shared.BaseExceptionHandler;
import co.edu.unicauca.piedrazul.backend.user.exception.*;
import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = UserController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class UserExceptionHandler extends BaseExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(
            MethodArgumentNotValidException ex,
            HttpServletRequest request
    ) {

        log.warn("Error de validación en user");

        return buildValidationProblem(
                ex,
                request,
                "user"
        );
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public ProblemDetail handleNotFound(
            EntityNotFoundException ex,
            HttpServletRequest request
    ) {

        log.warn(
                "Recurso no encontrado en user: {}",
                ex.getMessage()
        );

        return buildProblem(
                HttpStatus.NOT_FOUND,
                "No encontrado",
                ex.getMessage(),
                "user",
                "USER_MODULE_NOT_FOUND",
                request
        );
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(
            IllegalArgumentException ex,
            HttpServletRequest request
    ) {

        log.warn(
                "Argumento inválido en user: {}",
                ex.getMessage()
        );

        return buildProblem(
                HttpStatus.BAD_REQUEST,
                "Solicitud inválida",
                ex.getMessage(),
                "user",
                "INVALID_ARGUMENT",
                request
        );
    }

    @ExceptionHandler(UserBusinessException.class)
    public ProblemDetail handleBusinessException(
            UserBusinessException ex,
            HttpServletRequest request
    ) {

        log.warn(
                "Error de negocio en user [{}]: {}",
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