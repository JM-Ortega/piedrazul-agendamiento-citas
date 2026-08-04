package co.edu.unicauca.piedrazul.backend.doctors.api;

import co.edu.unicauca.piedrazul.backend.doctors.exception.DoctorNotFoundException;
import co.edu.unicauca.piedrazul.backend.doctors.exception.DoctorScheduleConflictException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class DoctorExceptionHandlerTest {

    private final DoctorExceptionHandler handler = new DoctorExceptionHandler();

    @Test
    void handleBusinessExceptionShouldReturnDomainStatus() {
        HttpServletRequest request = buildRequest("/api/doctor/123");

        ProblemDetail result = handler.handleBusinessException(
                new DoctorNotFoundException("Doctor no encontrado"),
                request
        );

        assertThat(result.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(result.getTitle()).isEqualTo(HttpStatus.NOT_FOUND.getReasonPhrase());
        assertThat(result.getDetail()).isEqualTo("Doctor no encontrado");
        assertThat(result.getProperties()).containsEntry("errorCode", "DOCTOR_NOT_FOUND");
        assertThat(result.getProperties()).containsEntry("module", "doctors");
    }

    @Test
    void handleBusinessExceptionShouldReturnConflictForScheduleIssues() {
        HttpServletRequest request = buildRequest("/api/doctor/schedules/123/LUNES");

        ProblemDetail result = handler.handleBusinessException(
                new DoctorScheduleConflictException("A schedule for LUNES already exists"),
                request
        );

        assertThat(result.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(result.getTitle()).isEqualTo(HttpStatus.CONFLICT.getReasonPhrase());
        assertThat(result.getDetail()).isEqualTo("A schedule for LUNES already exists");
        assertThat(result.getProperties()).containsEntry("errorCode", "DOCTOR_SCHEDULE_CONFLICT");
        assertThat(result.getProperties()).containsEntry("module", "doctors");
    }

    @Test
    void handleRuntimeShouldHideUnexpectedMessage() {
        HttpServletRequest request = buildRequest("/api/doctor/");

        ProblemDetail result = handler.handleRuntime(new RuntimeException("Sensitive detail"), request);

        assertThat(result.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(result.getTitle()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase());
        assertThat(result.getDetail()).isEqualTo("Unexpected error in doctors module");
        assertThat(result.getProperties()).containsEntry("errorCode", "INTERNAL_ERROR");
        assertThat(result.getProperties()).containsEntry("module", "doctors");
    }

    private HttpServletRequest buildRequest(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI(uri);
        return request;
    }
}