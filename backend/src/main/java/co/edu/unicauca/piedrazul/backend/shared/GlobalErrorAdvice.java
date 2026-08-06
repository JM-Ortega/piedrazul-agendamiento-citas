package co.edu.unicauca.piedrazul.backend.shared;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

// Si nadie captura la excepcion de alguno de los controladores llega aqui
@Order(Ordered.LOWEST_PRECEDENCE)
@RestControllerAdvice
@Slf4j
public class GlobalErrorAdvice extends BaseExceptionHandler {

    @ExceptionHandler(Exception.class)
    public ProblemDetail globalHandle (Exception ex, HttpServletRequest request){
        String detail = ex.getMessage();

        log.warn("Excepcion no controlada {}", detail);
        return buildProblem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Ocurrió un error inesperado en el servidor.",
                detail,
                "unknown module",
                "VALIDATION_ERROR",
                request
        );
    }
}
