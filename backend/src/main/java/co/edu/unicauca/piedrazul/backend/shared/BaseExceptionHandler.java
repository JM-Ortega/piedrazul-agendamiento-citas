package co.edu.unicauca.piedrazul.backend.shared;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;
import java.util.stream.Collectors;

// ProblemDetail, estándar definido en RFC 9457
public abstract class BaseExceptionHandler {

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
