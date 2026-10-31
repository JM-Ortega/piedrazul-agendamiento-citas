package co.edu.unicauca.piedrazul.backend.audit.api;

import co.edu.unicauca.piedrazul.backend.audit.exception.InvalidAuditCriteriaException;
import co.edu.unicauca.piedrazul.backend.shared.BaseExceptionHandler;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Errores de la consulta de auditoría. Sin este manejador, un filtro inválido (una acción
 * inexistente, una fecha mal escrita) caería en el manejador global y respondería 500.
 */
@RestControllerAdvice(basePackageClasses = AuditController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class AuditExceptionHandler extends BaseExceptionHandler {

    @ExceptionHandler(InvalidAuditCriteriaException.class)
    public ProblemDetail handleInvalidCriteria(InvalidAuditCriteriaException ex, HttpServletRequest request) {
        log.warn("Filtros de auditoría inválidos: {}", ex.getMessage());
        return buildProblem(
                ex.getStatus(),
                spanishTitle(ex.getStatus()),
                ex.getMessage(),
                ex.getModule(),
                ex.getErrorCode(),
                request
        );
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleInvalidParameter(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        log.warn("Parámetro inválido en la consulta de auditoría: {}={}", ex.getName(), ex.getValue());
        return buildProblem(
                org.springframework.http.HttpStatus.BAD_REQUEST,
                "Solicitud inválida",
                "El valor del parámetro '" + ex.getName() + "' no es válido",
                "audit",
                "INVALID_AUDIT_PARAMETER",
                request
        );
    }
}
