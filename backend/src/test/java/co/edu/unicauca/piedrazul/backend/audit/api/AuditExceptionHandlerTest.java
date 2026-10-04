package co.edu.unicauca.piedrazul.backend.audit.api;

import co.edu.unicauca.piedrazul.backend.audit.exception.InvalidAuditCriteriaException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import static org.assertj.core.api.Assertions.assertThat;

class AuditExceptionHandlerTest {

    private final AuditExceptionHandler handler = new AuditExceptionHandler();
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/audit");

    @Test
    void invalidCriteriaIsABadRequestWithItsOwnErrorCode() {
        ProblemDetail problem = handler.handleInvalidCriteria(
                new InvalidAuditCriteriaException("El rango de fechas no puede superar 90 días"), request);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getDetail()).contains("90 días");
        assertThat(problem.getProperties()).containsEntry("errorCode", "INVALID_AUDIT_CRITERIA");
    }

    @Test
    void aValueThatIsNotAnEnumConstantIsABadRequestNotAServerError() {
        var mismatch = new MethodArgumentTypeMismatchException("NO_EXISTE", Enum.class, "action", null, null);

        ProblemDetail problem = handler.handleInvalidParameter(mismatch, request);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getDetail()).contains("action");
        assertThat(problem.getProperties()).containsEntry("errorCode", "INVALID_AUDIT_PARAMETER");
    }
}
