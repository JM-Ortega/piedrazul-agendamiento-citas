package co.edu.unicauca.piedrazul.backend.statistics.exception;

import co.edu.unicauca.piedrazul.backend.shared.BaseExceptionHandler;
import co.edu.unicauca.piedrazul.backend.shared.BusinessException;
import co.edu.unicauca.piedrazul.backend.statistics.api.StatisticsController;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Las excepciones de negocio de appointment (año o estado inválido) también llegan aquí,
 * porque su handler solo cubre AppointmentController.
 */
@RestControllerAdvice(basePackageClasses = StatisticsController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class StatisticsExceptionHandler extends BaseExceptionHandler {

    private static final String MODULE = "statistics";

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ProblemDetail handleMissingParameter(MissingServletRequestParameterException ex, HttpServletRequest request) {
        log.warn("Parámetro faltante en el módulo statistics: {}", ex.getParameterName());
        return buildProblem(HttpStatus.BAD_REQUEST, "Solicitud inválida",
                "El parámetro '" + ex.getParameterName() + "' es obligatorio",
                MODULE, "MISSING_PARAMETER", request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        log.warn("Parámetro inválido en el módulo statistics: {}={}", ex.getName(), ex.getValue());
        return buildProblem(HttpStatus.BAD_REQUEST, "Solicitud inválida",
                "El parámetro '" + ex.getName() + "' tiene un valor inválido: " + ex.getValue(),
                MODULE, "INVALID_PARAMETER", request);
    }

    @ExceptionHandler(RuntimeException.class)
    public ProblemDetail handleBusiness(RuntimeException ex, HttpServletRequest request) {
        if (ex instanceof AuthorizationDeniedException e) throw e;
        if (ex instanceof AccessDeniedException e) throw e;

        String errorCode = (ex instanceof BusinessException be) ? be.getErrorCode() : "INTERNAL_ERROR";
        HttpStatus status = (ex instanceof BusinessException be) ? be.getStatus() : HttpStatus.INTERNAL_SERVER_ERROR;
        String module = (ex instanceof BusinessException be) ? be.getModule() : MODULE;

        if (status.is5xxServerError()) {
            log.error("Excepción no controlada en el módulo statistics", ex);
            return buildProblem(status, "Error interno del servidor",
                    "Ocurrió un error inesperado en el servidor.", module, errorCode, request);
        }

        log.warn("Error de negocio en el módulo statistics [{}]: {}", ex.getClass().getSimpleName(), ex.getMessage());
        return buildProblem(status, spanishTitle(status), ex.getMessage(), module, errorCode, request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
        log.warn("Mal envío de datos en el módulo statistics [{}]: {}", ex.getClass().getSimpleName(), ex.getMessage());
        return buildProblem(HttpStatus.BAD_REQUEST, "Solicitud inválida", ex.getMessage(),
                MODULE, "INVALID_ARGUMENT", request);
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex, HttpServletRequest request) {
        if (ex instanceof AuthorizationDeniedException e) throw e;
        if (ex instanceof AccessDeniedException e) throw e;

        log.error("Excepción no controlada en el módulo statistics", ex);
        return buildProblem(HttpStatus.INTERNAL_SERVER_ERROR, "Error interno del servidor",
                "Ocurrió un error inesperado en el servidor.", MODULE, "INTERNAL_SERVER_ERROR", request);
    }
}
