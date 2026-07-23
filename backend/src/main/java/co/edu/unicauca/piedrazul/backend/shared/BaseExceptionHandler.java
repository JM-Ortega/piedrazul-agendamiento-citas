package co.edu.unicauca.piedrazul.backend.shared;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.net.URI;
import java.time.Instant;

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
}
