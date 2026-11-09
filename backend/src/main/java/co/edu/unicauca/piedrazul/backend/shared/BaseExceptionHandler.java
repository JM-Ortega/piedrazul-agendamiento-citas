package co.edu.unicauca.piedrazul.backend.shared;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.servlet.HandlerMapping;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;
import java.util.stream.Collectors;

// ProblemDetail, estándar definido en RFC 9457
public abstract class BaseExceptionHandler {

    private static final String UNEXPECTED_DETAIL = "Ocurrió un error inesperado en el servidor.";

    /**
     * Respuesta y registro de una RuntimeException que no es de negocio. Su mensaje no llega al
     * cliente ni al log. Un cuerpo o un parámetro mal formado es un rechazo de la petición y no un
     * fallo del servidor, así que queda como aviso aunque su estado siga siendo 500.
     */
    protected ProblemDetail buildUnexpectedProblem(
            Logger log,
            RuntimeException ex,
            String module,
            HttpServletRequest request
    ) {
        if (ex instanceof HttpMessageNotReadableException || ex instanceof TypeMismatchException) {
            log.warn("Entrada rechazada en el módulo {} [{}]", module, ex.getClass().getSimpleName());
        } else {
            logUnexpected(log, "Excepción no controlada en el módulo " + module, ex, request);
        }

        return buildProblem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                spanishTitle(HttpStatus.INTERNAL_SERVER_ERROR),
                UNEXPECTED_DETAIL,
                module,
                "INTERNAL_ERROR",
                request
        );
    }

    /** Registra un fallo inesperado con su ruta declarada, nunca la URI con sus valores. */
    protected void logUnexpected(Logger log, String event, Exception ex, HttpServletRequest request) {
        Object route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);

        log.error(
                "{} [{} {}]",
                event,
                request.getMethod(),
                route != null ? route : "sin ruta",
                SanitizedThrowable.of(ex)
        );
    }

    protected ProblemDetail buildProblem(
            HttpStatus status,
            String title,
            String detail,
            String module,
            String errorCode,
            HttpServletRequest request
    ) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);

        String typeUri = String.format(
                "https://piedrazul/errors/%s/%s",
                module,
                errorCode.toLowerCase().replace("_", "-")
        );

        problem.setType(URI.create(typeUri));
        problem.setInstance(URI.create(request.getRequestURI()));

        problem.setProperty("errorCode", errorCode);
        problem.setProperty("module", module);
        problem.setProperty("timestamp", Instant.now());

        return problem;
    }

    protected static String spanishTitle(HttpStatus status) {
        return switch (status) {
            case BAD_REQUEST -> "Solicitud inválida";
            case UNAUTHORIZED -> "No autorizado";
            case FORBIDDEN -> "Prohibido";
            case NOT_FOUND -> "No encontrado";
            case CONFLICT -> "Conflicto";
            case TOO_MANY_REQUESTS -> "Demasiadas solicitudes";
            case INTERNAL_SERVER_ERROR -> "Error interno del servidor";
            case SERVICE_UNAVAILABLE -> "Servicio no disponible";
            default -> status.getReasonPhrase();
        };
    }

    protected ProblemDetail buildValidationProblem(
            MethodArgumentNotValidException ex,
            HttpServletRequest request,
            String module
    ) {
        String detail = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(FieldError::getDefaultMessage)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.joining("; "));

        if (detail.isBlank()) {
            detail = "La solicitud contiene errores de validación";
        }

        return buildProblem(
                HttpStatus.BAD_REQUEST,
                "Error de validación",
                detail,
                module,
                "VALIDATION_ERROR",
                request
        );
    }
}
