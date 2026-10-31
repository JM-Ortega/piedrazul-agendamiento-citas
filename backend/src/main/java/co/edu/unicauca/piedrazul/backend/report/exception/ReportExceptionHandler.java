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

        String errorCode = (ex instanceof BusinessException be) ? be.getErrorCode() : "INTERNAL_ERROR";
        HttpStatus status = (ex instanceof BusinessException be) ? be.getStatus() : HttpStatus.INTERNAL_SERVER_ERROR;
        String module = (ex instanceof BusinessException be) ? be.getModule() : "Report";

        log.warn("Error de negocio en el módulo Report [{}]: {}",
                ex.getClass().getSimpleName(), ex.getMessage());

        return buildProblem(status, spanishTitle(status), ex.getMessage(),
                module, errorCode, request);
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

        log.error("Excepción no controlada en el módulo Report", ex);

        return buildProblem(HttpStatus.INTERNAL_SERVER_ERROR, "Error interno del servidor",
                "Ocurrió un error inesperado en el servidor.", "Report",
                "INTERNAL_SERVER_ERROR", request);
    }
}
