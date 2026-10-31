package co.edu.unicauca.piedrazul.backend.shared;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

// Si nadie captura la excepcion de alguno de los controladores llega aqui
@Order(Ordered.LOWEST_PRECEDENCE)
@RestControllerAdvice
@Slf4j
public class GlobalErrorAdvice extends BaseExceptionHandler {

    @ExceptionHandler(Exception.class)
    public ProblemDetail globalHandle(
            Exception ex,
            HttpServletRequest request
    ) {

        log.error(
                "Excepción no controlada",
                ex
        );

        return buildProblem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Error interno del servidor",
                "Ocurrió un error inesperado en el servidor.",
                "global",
                "INTERNAL_ERROR",
                request
        );
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ProblemDetail handleNoResourceFound(
            NoResourceFoundException ex,
            HttpServletRequest request
    ) {

        log.warn(
                "Ruta no encontrada: {} {}",
                request.getMethod(),
                request.getRequestURI()
        );

        return buildProblem(
                HttpStatus.NOT_FOUND,
                "Recurso no encontrado",
                "La ruta solicitada no existe",
                "global",
                "RESOURCE_NOT_FOUND",
                request
        );
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ProblemDetail handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex,
            HttpServletRequest request
    ) {

        log.warn(
                "Método HTTP no permitido: {} {}",
                request.getMethod(),
                request.getRequestURI()
        );

        return buildProblem(
                HttpStatus.METHOD_NOT_ALLOWED,
                "Método HTTP no permitido",
                "El método HTTP utilizado no está permitido para esta ruta",
                "global",
                "METHOD_NOT_ALLOWED",
                request
        );
    }
}
