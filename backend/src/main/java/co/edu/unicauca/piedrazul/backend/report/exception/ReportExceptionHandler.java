package co.edu.unicauca.piedrazul.backend.report.exception;

import co.edu.unicauca.piedrazul.backend.report.api.ReportController;
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
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = ReportController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class ReportExceptionHandler extends BaseExceptionHandler {

    @ExceptionHandler(RuntimeException.class)
    public ProblemDetail handleBusiness(RuntimeException ex, HttpServletRequest request) {

        if (ex instanceof AuthorizationDeniedException e) throw e;
        if (ex instanceof AccessDeniedException e) throw e;

        if (!(ex instanceof BusinessException be)) {
            return buildUnexpectedProblem(log, ex, "Report", request);
        }

        log.warn("Error de negocio en el módulo Report [{}]: {}",
                ex.getClass().getSimpleName(), ex.getMessage());

        return buildProblem(be.getStatus(), spanishTitle(be.getStatus()), ex.getMessage(),
                be.getModule(), be.getErrorCode(), request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {

        log.warn("Mal envío de datos en el módulo Report [{}]: {}",
                ex.getClass().getSimpleName(), ex.getMessage());

        return buildProblem(HttpStatus.BAD_REQUEST, "Solicitud inválida", ex.getMessage(),
                "Report", "INVALID_ARGUMENT", request);
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex, HttpServletRequest request) {

        if (ex instanceof AuthorizationDeniedException e) throw e;
        if (ex instanceof AccessDeniedException e) throw e;

        logUnexpected(log, "Excepción no controlada en el módulo Report", ex, request);

        return buildProblem(HttpStatus.INTERNAL_SERVER_ERROR, "Error interno del servidor",
                "Ocurrió un error inesperado en el servidor.", "Report",
                "INTERNAL_SERVER_ERROR", request);
    }
}
