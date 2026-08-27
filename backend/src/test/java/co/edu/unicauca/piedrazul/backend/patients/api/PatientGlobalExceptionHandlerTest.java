package co.edu.unicauca.piedrazul.backend.patients.api;

import co.edu.unicauca.piedrazul.backend.shared.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class PatientGlobalExceptionHandlerTest {

    private final PatientGlobalExceptionHandler handler = new PatientGlobalExceptionHandler();

    @Test
    void handleIllegalArgumentShouldReturnBadRequestProblemDetail() {
        IllegalArgumentException exception = new IllegalArgumentException("documentNumber is required");
        HttpServletRequest request = buildRequest("/api/patients");

        ProblemDetail result = handler.handleIllegalArgument(exception, request);

        assertThat(result.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(result.getTitle()).isEqualTo("Bad request");
        assertThat(result.getDetail()).isEqualTo("documentNumber is required");
        assertThat(result.getType().toString()).isEqualTo("https://piedrazul/errors/patients/invalid-argument");
        assertThat(result.getInstance().toString()).isEqualTo("/api/patients");
        assertThat(result.getProperties()).containsEntry("errorCode", "INVALID_ARGUMENT");
        assertThat(result.getProperties()).containsEntry("module", "patients");
        assertThat(result.getProperties()).containsKey("timestamp");
    }

    @Test
    void handleIllegalStateShouldReturnConflictProblemDetail() {
        IllegalStateException exception = new IllegalStateException("patient already has a user linked");
        HttpServletRequest request = buildRequest("/api/patients/link-user-account");

        ProblemDetail result = handler.handleIllegalState(exception, request);

        assertThat(result.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(result.getTitle()).isEqualTo("Conflict");
        assertThat(result.getDetail()).isEqualTo("patient already has a user linked");
        assertThat(result.getType().toString()).isEqualTo("https://piedrazul/errors/patients/invalid-state");
        assertThat(result.getInstance().toString()).isEqualTo("/api/patients/link-user-account");
        assertThat(result.getProperties()).containsEntry("errorCode", "INVALID_STATE");
        assertThat(result.getProperties()).containsEntry("module", "patients");
        assertThat(result.getProperties()).containsKey("timestamp");
    }

    @Test
    void handleRuntimeShouldReturnBusinessExceptionProblemDetailWhenExceptionImplementsBusinessException() {
        TestPatientBusinessRuntimeException exception =
                new TestPatientBusinessRuntimeException("Patient not found");

        HttpServletRequest request = buildRequest("/api/patients/123");

        ProblemDetail result = handler.handleRuntime(exception, request);

        assertThat(result.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(result.getTitle()).isEqualTo(HttpStatus.NOT_FOUND.getReasonPhrase());
        assertThat(result.getDetail()).isEqualTo("Patient not found");
        assertThat(result.getType().toString()).isEqualTo("https://piedrazul/errors/patients/patient-not-found");
        assertThat(result.getInstance().toString()).isEqualTo("/api/patients/123");
        assertThat(result.getProperties()).containsEntry("errorCode", "PATIENT_NOT_FOUND");
        assertThat(result.getProperties()).containsEntry("module", "patients");
        assertThat(result.getProperties()).containsKey("timestamp");
    }

    @Test
    void handleRuntimeShouldReturnInternalServerErrorWhenExceptionIsGeneric() {
        RuntimeException exception = new RuntimeException("Unexpected error");
        HttpServletRequest request = buildRequest("/api/patients");

        ProblemDetail result = handler.handleRuntime(exception, request);

        assertThat(result.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(result.getTitle()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase());
        assertThat(result.getDetail()).isEqualTo("Unexpected error");
        assertThat(result.getType().toString()).isEqualTo("https://piedrazul/errors/patients/internal-error");
        assertThat(result.getInstance().toString()).isEqualTo("/api/patients");
        assertThat(result.getProperties()).containsEntry("errorCode", "INTERNAL_ERROR");
        assertThat(result.getProperties()).containsEntry("module", "patients");
        assertThat(result.getProperties()).containsKey("timestamp");
    }

    private HttpServletRequest buildRequest(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI(uri);
        return request;
    }

    private static class TestPatientBusinessRuntimeException extends RuntimeException implements BusinessException {

        public TestPatientBusinessRuntimeException(String message) {
            super(message);
        }

        @Override
        public String getErrorCode() {
            return "PATIENT_NOT_FOUND";
        }

        @Override
        public HttpStatus getStatus() {
            return HttpStatus.NOT_FOUND;
        }

        @Override
        public String getModule() {
            return "patients";
        }
    }
}