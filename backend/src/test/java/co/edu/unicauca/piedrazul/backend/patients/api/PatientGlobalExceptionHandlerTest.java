package co.edu.unicauca.piedrazul.backend.patients.api;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import co.edu.unicauca.piedrazul.backend.shared.BusinessException;
import co.edu.unicauca.piedrazul.backend.verification.exception.VerificationDeliveryUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class PatientGlobalExceptionHandlerTest {

    private final PatientGlobalExceptionHandler handler = new PatientGlobalExceptionHandler();

    private final Logger handlerLogger = (Logger) LoggerFactory.getLogger(PatientGlobalExceptionHandler.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void attachAppender() {
        appender.start();
        handlerLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        handlerLogger.detachAppender(appender);
    }

    @Test
    void knownOtpDeliveryUnavailabilityKeepsItsContractWithoutStackTrace() {
        ProblemDetail result = handler.handleRuntime(
                new VerificationDeliveryUnavailableException(), buildRequest("/api/patients/link-user-account/request-code"));

        assertThat(result.getStatus()).isEqualTo(503);
        assertThat(result.getTitle()).isEqualTo("Servicio no disponible");
        assertThat(result.getDetail()).isEqualTo(
                "El servicio de envío de códigos no está disponible temporalmente. Inténtalo más tarde.");
        assertThat(result.getProperties()).containsEntry("errorCode", "VERIFICATION_DELIVERY_UNAVAILABLE");
        assertThat(result.getProperties()).containsEntry("module", "verification");
        assertThat(appender.list).noneMatch(event -> event.getLevel().isGreaterOrEqual(Level.WARN));
        assertThat(appender.list).allMatch(event -> event.getThrowableProxy() == null);
    }

    @Test
    void anUnrelated503IsStillAnErrorWithItsStackTrace() {
        ProblemDetail result = handler.handleRuntime(
                new TestBusinessException("Dependencia sintética caída", "SYNTHETIC_DEPENDENCY_DOWN", HttpStatus.SERVICE_UNAVAILABLE),
                buildRequest("/api/patients"));

        assertThat(result.getStatus()).isEqualTo(503);
        assertThat(result.getProperties()).containsEntry("errorCode", "SYNTHETIC_DEPENDENCY_DOWN");
        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.getFirst().getLevel()).isEqualTo(Level.ERROR);
        assertThat(appender.list.getFirst().getThrowableProxy()).isNotNull();
    }

    @Test
    void the503SuppressionDependsOnTheErrorCodeNotOnTheModule() {
        // Mismo módulo y estado que el caso conocido, otro errorCode: no se silencia.
        handler.handleRuntime(
                new TestBusinessException("Otro fallo", "VERIFICATION_SOMETHING_ELSE", HttpStatus.SERVICE_UNAVAILABLE),
                buildRequest("/api/patients"));

        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getThrowableProxy()).isNotNull();
        });
    }

    @Test
    void anUnexpected500IsStillAnErrorWithItsStackTrace() {
        handler.handleRuntime(new RuntimeException("boom"), buildRequest("/api/patients"));

        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getThrowableProxy()).isNotNull();
        });
    }

    @Test
    void anIdentityProvider502IsStillAnErrorWithItsStackTrace() {
        ProblemDetail result = handler.handleRuntime(
                new co.edu.unicauca.piedrazul.backend.user.exception.IdentityProviderException("synthetic"),
                buildRequest("/api/patients"));

        assertThat(result.getStatus()).isEqualTo(502);
        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getThrowableProxy()).isNotNull();
        });
    }

    @Test
    void handleIllegalArgumentShouldReturnBadRequestProblemDetail() {
        IllegalArgumentException exception = new IllegalArgumentException("documentNumber is required");
        HttpServletRequest request = buildRequest("/api/patients");

        ProblemDetail result = handler.handleIllegalArgument(exception, request);

        assertThat(result.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(result.getTitle()).isEqualTo("Solicitud inválida");
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
        assertThat(result.getTitle()).isEqualTo("Conflicto");
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
        assertThat(result.getTitle()).isEqualTo("No encontrado");
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
        assertThat(result.getTitle()).isEqualTo("Error interno del servidor");
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

    private static class TestBusinessException extends RuntimeException implements BusinessException {

        private final String errorCode;
        private final HttpStatus status;

        TestBusinessException(String message, String errorCode, HttpStatus status) {
            super(message);
            this.errorCode = errorCode;
            this.status = status;
        }

        @Override
        public String getErrorCode() {
            return errorCode;
        }

        @Override
        public HttpStatus getStatus() {
            return status;
        }

        @Override
        public String getModule() {
            return "verification";
        }
    }
}
