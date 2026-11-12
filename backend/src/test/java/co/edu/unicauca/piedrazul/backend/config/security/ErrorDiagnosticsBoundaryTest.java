package co.edu.unicauca.piedrazul.backend.config.security;

import co.edu.unicauca.piedrazul.backend.appointment.AppointmentExternalService;
import co.edu.unicauca.piedrazul.backend.appointment.application.AppointmentSchedulingService;
import co.edu.unicauca.piedrazul.backend.appointment.application.CancelAppointmentUseCaseImpl;
import co.edu.unicauca.piedrazul.backend.appointment.application.UpdateAppointmentStatusUseCaseImpl;
import co.edu.unicauca.piedrazul.backend.appointment.application.scheduling.AutonomousPatientResolutionStrategy;
import co.edu.unicauca.piedrazul.backend.appointment.application.scheduling.ManualPatientResolutionStrategy;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentState;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentTime;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.SchedulingOrigin;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.CancelAppointmentUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.CheckExistenceByDocAndStateUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.CountScheduledAppointmentsUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.GetAppointmentStatesUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.GetAutonomousSchedulingContidionUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.GetDoctorDailyAgendaUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.IsNewPatientUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.ListAppointmentsUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.RegisterUnscheduledAttentionUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.UpdateAppointmentStatusUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.UpdateAutonomousSchedulingUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentRepository;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.ClinicalHistoryPort;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.DoctorConfigConsultPort;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.PatientConsultPort;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.AppointmentController;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.mappers.CitaDtoMapper;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditEvent;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditEventRepository;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditOutcome;
import co.edu.unicauca.piedrazul.backend.audit.infrastructure.aop.AuditableAspect;
import co.edu.unicauca.piedrazul.backend.config.web.CorsConfig;
import co.edu.unicauca.piedrazul.backend.patients.api.PatientController;
import co.edu.unicauca.piedrazul.backend.patients.application.PatientService;
import co.edu.unicauca.piedrazul.backend.patients.application.PatientUpdateService;
import co.edu.unicauca.piedrazul.backend.patients.exception.PatientNotFoundException;
import co.edu.unicauca.piedrazul.backend.shared.audit.CorrelationIdFilter;
import co.edu.unicauca.piedrazul.backend.shared.audit.SecurityContextExtractor;
import co.edu.unicauca.piedrazul.backend.shared.enums.SpecialtyCode;
import co.edu.unicauca.piedrazul.backend.support.DiagnosticsProbeEndpoints;
import co.edu.unicauca.piedrazul.backend.user.PersonExternalService;
import co.edu.unicauca.piedrazul.backend.user.exception.IdentityProviderException;
import co.edu.unicauca.piedrazul.backend.verification.exception.VerificationDeliveryUnavailableException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.servlet.autoconfigure.HttpEncodingAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.error.ErrorMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Controller;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Respuestas y registros de error por la cadena real: Tomcat, {@code SecurityConfig}, el filtro de
 * correlación, el aspecto de auditoría y los manejadores tal como los ordena el escaneo de la
 * aplicación, porque de ese orden depende quién atiende cada excepción. Los controladores de
 * pacientes y citas, y los casos de uso de cancelación y cambio de estado, son los reales.
 */
@SpringBootTest(
        classes = ErrorDiagnosticsBoundaryTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
@MockitoBean(types = {
        ListAppointmentsUseCase.class, CitaDtoMapper.class, IsNewPatientUseCase.class,
        GetAppointmentStatesUseCase.class, UpdateAutonomousSchedulingUseCase.class,
        GetAutonomousSchedulingContidionUseCase.class, RegisterUnscheduledAttentionUseCase.class,
        GetDoctorDailyAgendaUseCase.class, CountScheduledAppointmentsUseCase.class,
        CheckExistenceByDocAndStateUseCase.class, AppointmentSchedulingService.class,
        ManualPatientResolutionStrategy.class, AutonomousPatientResolutionStrategy.class,
        PatientConsultPort.class, DoctorConfigConsultPort.class, ClinicalHistoryPort.class,
        PatientUpdateService.class, AppointmentExternalService.class, PersonExternalService.class})
class ErrorDiagnosticsBoundaryTest {

    private static final String MARKER = DiagnosticsProbeEndpoints.MARKER;
    private static final String FALLBACK = "Ocurrió un error inesperado en el servidor.";
    private static final String DOCUMENT_ROUTE = "/api/patients/document/" + MARKER;

    @SpringBootConfiguration
    @EnableAspectJAutoProxy
    @ImportAutoConfiguration({
            PropertyPlaceholderAutoConfiguration.class,
            TomcatServletWebServerAutoConfiguration.class,
            DispatcherServletAutoConfiguration.class,
            HttpEncodingAutoConfiguration.class,
            HttpMessageConvertersAutoConfiguration.class,
            JacksonAutoConfiguration.class,
            WebMvcAutoConfiguration.class,
            ErrorMvcAutoConfiguration.class,
            SecurityAutoConfiguration.class,
            ServletWebSecurityAutoConfiguration.class,
            SecurityFilterAutoConfiguration.class
    })
    @Import({
            SecurityConfig.class,
            JwtAuthConverter.class,
            CorsConfig.class,
            CorrelationIdFilter.class,
            SecurityContextExtractor.class,
            AuditableAspect.class,
            PatientController.class,
            AppointmentController.class
    })
    @ComponentScan(
            basePackages = "co.edu.unicauca.piedrazul.backend",
            useDefaultFilters = false,
            includeFilters = {
                    @ComponentScan.Filter(type = FilterType.ANNOTATION, classes = RestControllerAdvice.class),
                    @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*DiagnosticsProbe")
            })
    static class Config {

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> switch (token) {
                case "ADMIN", "DOCTOR", "PATIENT", "SCHEDULER" -> Jwt.withTokenValue("sintetico")
                        .header("alg", "none")
                        .subject(UUID.randomUUID().toString())
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(60))
                        .claim("realm_access", Map.of("roles", List.of(token)))
                        .build();
                default -> throw new BadJwtException("token de prueba inválido");
            };
        }

        @Bean
        CancelAppointmentUseCase cancelAppointmentUseCase(AppointmentRepository repository) {
            return new CancelAppointmentUseCaseImpl(repository);
        }

        @Bean
        UpdateAppointmentStatusUseCase updateAppointmentStatusUseCase(
                AppointmentRepository repository,
                DoctorConfigConsultPort doctorConfigConsultPort,
                ClinicalHistoryPort clinicalHistoryPort) {
            return new UpdateAppointmentStatusUseCaseImpl(repository, doctorConfigConsultPort, clinicalHistoryPort);
        }
    }

    /** Fuera de los paquetes con manejador propio: la atiende el manejador global. */
    @Controller
    @ResponseBody
    @RequestMapping("/probe/global")
    static class GlobalDiagnosticsProbe extends DiagnosticsProbeEndpoints {
    }

    @LocalServerPort
    private int port;

    @MockitoBean
    private AppointmentRepository appointmentRepository;

    @MockitoBean
    private PatientService patientService;

    @MockitoBean
    private AuditEventRepository auditEventRepository;

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private HttpResponse<String> send(String method, String path, String role, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (role != null) {
            request.header("Authorization", "Bearer " + role);
        }
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String role) throws Exception {
        return send("GET", path, role, null);
    }

    private JsonNode problem(HttpResponse<String> response) throws Exception {
        return json.readTree(response.body());
    }

    private void assertProblem(HttpResponse<String> response, int status, String errorCode, String module,
                               String detail) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        JsonNode problem = problem(response);
        assertThat(problem.get("status").asInt()).isEqualTo(status);
        assertThat(problem.get("errorCode").asText()).isEqualTo(errorCode);
        assertThat(problem.get("module").asText()).isEqualTo(module);
        assertThat(problem.get("detail").asText()).isEqualTo(detail);
    }

    private static String correlationOf(HttpResponse<String> response) {
        return response.headers().firstValue("X-Correlation-Id").orElseThrow();
    }

    private static List<String> linesAtLevel(CapturedOutput output, String level) {
        return output.getAll().lines().filter(line -> line.contains(" " + level + " ")).toList();
    }

    private static Stream<Arguments> modulesWithUnexpectedHandling() {
        return Stream.of(
                Arguments.of("appointment", "appointment", "AppointmentExceptionHandler"),
                Arguments.of("medical", "clinicalHistory", "MedicalCheckupExceptionHandler"),
                Arguments.of("report", "Report", "ReportExceptionHandler"),
                Arguments.of("patients", "patients", "PatientGlobalExceptionHandler"),
                Arguments.of("statistics", "statistics", "StatisticsExceptionHandler"),
                Arguments.of("global", "global", "GlobalErrorAdvice"));
    }

    // ── Fallos inesperados ───────────────────────────────────────────────

    @ParameterizedTest
    @MethodSource("modulesWithUnexpectedHandling")
    void anUnexpectedFailureIsSafeForTheClientAndUsefulForTheOperator(
            String probe, String module, String handler, CapturedOutput output) throws Exception {
        HttpResponse<String> response = get("/probe/" + probe + "/failure", "ADMIN");

        assertProblem(response, 500, "INTERNAL_ERROR", module, FALLBACK);
        JsonNode problem = problem(response);
        assertThat(problem.get("title").asText()).isEqualTo("Error interno del servidor");
        assertThat(problem.get("type").asText()).isEqualTo("https://piedrazul/errors/" + module + "/internal-error");
        assertThat(problem.get("instance").asText()).isEqualTo("/probe/" + probe + "/failure");
        assertThat(response.body()).doesNotContain(MARKER);

        assertThat(output.getAll())
                .doesNotContain(MARKER)
                .contains("org.springframework.dao.DataIntegrityViolationException")
                .contains("org.postgresql.util.PSQLException [SQLState=23505]")
                .contains("DiagnosticsProbeEndpoints.failure");
        assertThat(linesAtLevel(output, "ERROR")).singleElement().satisfies(line -> assertThat(line)
                .contains(handler)
                .contains("[" + correlationOf(response) + "]")
                .contains("Excepción no controlada")
                .contains("[GET /probe/" + probe + "/failure]"));
    }

    // patients no maneja Exception: Spring resuelve por la causa, que aquí es una RuntimeException.
    @ParameterizedTest
    @CsvSource({
            "appointment, appointment, INTERNAL_SERVER_ERROR, Error interno del servidor",
            "medical, clinicalHistory, INTERNAL_SERVER_ERROR, Ocurrió un error inesperado en el servidor.",
            "report, Report, INTERNAL_SERVER_ERROR, Ocurrió un error inesperado en el servidor.",
            "statistics, statistics, INTERNAL_SERVER_ERROR, Ocurrió un error inesperado en el servidor.",
            "patients, patients, INTERNAL_ERROR, Ocurrió un error inesperado en el servidor.",
            "global, global, INTERNAL_ERROR, Ocurrió un error inesperado en el servidor."
    })
    void aCheckedFailureKeepsItsResponseAndLogsNoExceptionText(
            String probe, String module, String errorCode, String detail, CapturedOutput output) throws Exception {
        HttpResponse<String> response = get("/probe/" + probe + "/checked", "ADMIN");

        assertProblem(response, 500, errorCode, module, detail);
        assertThat(output.getAll())
                .doesNotContain(MARKER)
                .contains("org.springframework.dao.DataIntegrityViolationException");
        assertThat(linesAtLevel(output, "ERROR")).hasSize(1);
    }

    @ParameterizedTest
    @CsvSource({"appointment, appointment", "medical, clinicalHistory", "report, Report"})
    void anUnrelatedIllegalStateIsAnUnexpectedFailure(String probe, String module, CapturedOutput output)
            throws Exception {
        HttpResponse<String> response = get("/probe/" + probe + "/illegal-state", "ADMIN");

        assertProblem(response, 500, "INTERNAL_ERROR", module, FALLBACK);
        assertThat(output.getAll()).doesNotContain(MARKER).contains("java.lang.IllegalStateException");
        assertThat(linesAtLevel(output, "ERROR")).hasSize(1);
    }

    // ── Entrada mal formada: mismo estado, sin texto y sin ERROR ─────────

    @ParameterizedTest
    @CsvSource({
            "appointment, appointment",
            "medical, clinicalHistory",
            "report, Report",
            "patients, patients"
    })
    void aMalformedBodyKeepsItsStatusWithoutEchoingTheValue(String probe, String module, CapturedOutput output)
            throws Exception {
        HttpResponse<String> response = send("POST", "/probe/" + probe + "/dated", "ADMIN",
                "{\"date\":\"" + MARKER + "\"}");

        assertProblem(response, 500, "INTERNAL_ERROR", module, FALLBACK);
        assertThat(response.body()).doesNotContain(MARKER);
        assertThat(output.getAll()).doesNotContain(MARKER).doesNotContain("\tat ");
        assertThat(linesAtLevel(output, "ERROR")).isEmpty();
        assertThat(linesAtLevel(output, "WARN")).singleElement().satisfies(line -> assertThat(line)
                .contains("Entrada rechazada")
                .contains("[" + correlationOf(response) + "]"));
    }

    @ParameterizedTest
    @CsvSource({
            "appointment, appointment",
            "medical, clinicalHistory",
            "report, Report",
            "patients, patients"
    })
    void aMalformedParameterKeepsItsStatusWithoutLoggingTheValue(String probe, String module, CapturedOutput output)
            throws Exception {
        HttpResponse<String> response = get("/probe/" + probe + "/resources/" + MARKER, "ADMIN");

        assertProblem(response, 500, "INTERNAL_ERROR", module, FALLBACK);
        assertThat(output.getAll()).doesNotContain(MARKER).doesNotContain("\tat ");
        assertThat(linesAtLevel(output, "ERROR")).isEmpty();
    }

    // ── Pacientes: respuesta intacta, aviso sin el documento ─────────────

    @Test
    void aLookupByDocumentKeepsItsResponseAndLeavesTheDocumentOutOfTheWarning(CapturedOutput output) throws Exception {
        when(patientService.findByDocumentNumber(MARKER)).thenReturn(Optional.empty());

        HttpResponse<String> response = get(DOCUMENT_ROUTE, "SCHEDULER");

        assertProblem(response, 404, "PATIENT_NOT_FOUND", "patients",
                "Paciente no encontrado con numero de documento: " + MARKER);
        assertThat(problem(response).get("title").asText()).isEqualTo("No encontrado");
        assertThat(output.getAll()).doesNotContain(MARKER);
        assertThat(linesAtLevel(output, "WARN")).singleElement().satisfies(line -> assertThat(line)
                .contains("PatientNotFoundException")
                .contains("PATIENT_NOT_FOUND")
                .contains("[" + correlationOf(response) + "]"));
    }

    @Test
    void theAnonymousPublicLookupKeepsItsResponse(CapturedOutput output) throws Exception {
        when(patientService.findPublicByDocumentNumber(MARKER)).thenThrow(new PatientNotFoundException(MARKER));

        HttpResponse<String> response = get(DOCUMENT_ROUTE + "/public", null);

        assertProblem(response, 404, "PATIENT_NOT_FOUND", "patients",
                "Paciente no encontrado con numero de documento: " + MARKER);
        assertThat(output.getAll()).doesNotContain(MARKER);
    }

    @Test
    void aLookupByIdKeepsItsResponse() throws Exception {
        UUID id = UUID.randomUUID();
        when(patientService.findById(id)).thenReturn(Optional.empty());

        HttpResponse<String> response = get("/api/patients/" + id, "SCHEDULER");

        assertProblem(response, 404, "PATIENT_NOT_FOUND", "patients", "Paciente no encontrado con id: " + id);
    }

    @Test
    void otpUnavailabilityKeepsItsResponseAndStaysOutOfWarnings(CapturedOutput output) throws Exception {
        doThrow(new VerificationDeliveryUnavailableException())
                .when(patientService).requestLinkUserAccountCode(anyString());

        HttpResponse<String> response = send("POST", "/api/patients/link-user-account/request-code", null,
                "{\"identification\":\"12345678\"}");

        assertProblem(response, 503, "VERIFICATION_DELIVERY_UNAVAILABLE", "verification",
                "El servicio de envío de códigos no está disponible temporalmente. Inténtalo más tarde.");
        assertThat(linesAtLevel(output, "WARN")).isEmpty();
        assertThat(linesAtLevel(output, "ERROR")).isEmpty();
    }

    @Test
    void anIdentityProviderFailureKeepsItsResponse(CapturedOutput output) throws Exception {
        when(patientService.findPublicByDocumentNumber(anyString()))
                .thenThrow(new IdentityProviderException("No se pudo crear el usuario"));

        HttpResponse<String> response = get("/api/patients/document/12345678/public", null);

        assertProblem(response, 502, "KEYCLOAK_EXCEPTION", "user",
                "Error del proveedor de identidad: No se pudo crear el usuario");
        assertThat(linesAtLevel(output, "ERROR")).hasSize(1);
    }

    // ── Conflictos de estado de citas ────────────────────────────────────

    private UUID appointmentIn(AppointmentState state) {
        UUID id = UUID.randomUUID();
        when(appointmentRepository.findByIdForUpdate(id)).thenReturn(Appointment.reconstruct(
                id, UUID.randomUUID(), UUID.randomUUID(), SpecialtyCode.FISIOTERAPIA, state,
                LocalDate.now().plusDays(1), new AppointmentTime(LocalTime.of(9, 0)), SchedulingOrigin.MANUAL));
        return id;
    }

    @Test
    void cancellingAnAttendedAppointmentKeepsItsExplanation(CapturedOutput output) throws Exception {
        UUID id = appointmentIn(AppointmentState.ATENDIDA);

        HttpResponse<String> response = send("PUT", "/api/appointments/" + id + "/cancel", "SCHEDULER", null);

        assertProblem(response, 500, "INTERNAL_ERROR", "appointment",
                "Solo las citas AGENDADAS pueden cancelarse. Estado actual: ATENDIDA");
        assertThat(problem(response).get("title").asText()).isEqualTo("Error interno del servidor");
        assertThat(problem(response).get("type").asText())
                .isEqualTo("https://piedrazul/errors/appointment/internal-error");
        assertThat(linesAtLevel(output, "ERROR")).isEmpty();
        assertThat(linesAtLevel(output, "WARN")).hasSize(1);
        verify(appointmentRepository, never()).save(any());
    }

    @ParameterizedTest
    @CsvSource({"mark-as-attended", "mark-as-unassisted"})
    void aStatusChangeOnAClosedAppointmentKeepsItsExplanation(String action, CapturedOutput output) throws Exception {
        UUID id = appointmentIn(AppointmentState.CANCELADA);

        HttpResponse<String> response = send("PUT", "/api/appointments/" + id + "/" + action, "DOCTOR", null);

        assertProblem(response, 500, "INTERNAL_ERROR", "appointment",
                "Solo se puede cambiar el estado de citas agendadas");
        assertThat(linesAtLevel(output, "ERROR")).isEmpty();
        verify(appointmentRepository, never()).save(any());
    }

    @Test
    void aConflictOnAnOnlyDeniedOperationIsNotAudited() throws Exception {
        UUID id = appointmentIn(AppointmentState.CANCELADA);

        send("PUT", "/api/appointments/" + id + "/mark-as-attended", "DOCTOR", null);

        verify(auditEventRepository, never()).save(any());
    }

    @Test
    void aDeniedStatusChangeIsStillAuditedAsDenied() throws Exception {
        HttpResponse<String> response =
                send("PUT", "/api/appointments/" + UUID.randomUUID() + "/mark-as-attended", "PATIENT", null);

        assertThat(response.statusCode()).isEqualTo(403);
        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditEventRepository).save(event.capture());
        assertThat(event.getValue().getOutcome()).isEqualTo(AuditOutcome.DENEGADO);
    }

    // ── 404 y 405 ────────────────────────────────────────────────────────

    @Test
    void aRealMethodNotAllowedKeepsItsResponseAndLeavesThePathOutOfTheWarning(CapturedOutput output) throws Exception {
        HttpResponse<String> response = send("DELETE", DOCUMENT_ROUTE, "PATIENT", null);

        assertProblem(response, 405, "METHOD_NOT_ALLOWED", "global",
                "El método HTTP utilizado no está permitido para esta ruta");
        assertThat(problem(response).get("instance").asText()).isEqualTo(DOCUMENT_ROUTE);
        assertThat(output.getAll()).doesNotContain(MARKER);
        assertThat(linesAtLevel(output, "WARN")).singleElement().satisfies(line -> assertThat(line)
                .contains("Método HTTP no permitido: DELETE")
                .contains("[" + correlationOf(response) + "]"));
    }

    @ParameterizedTest
    @CsvSource({
            "/api/patients/document/SYN0000001/inexistente, PATIENT",
            "/SYN0000001, PATIENT",
            "/api/SYN0000001, PATIENT",
            "/swagger-ui/SYN0000001,"
    })
    void aNotFoundRouteKeepsItsResponseAndLeavesThePathOutOfTheWarning(String path, String role, CapturedOutput output)
            throws Exception {
        HttpResponse<String> response = get(path, role);

        assertProblem(response, 404, "RESOURCE_NOT_FOUND", "global", "La ruta solicitada no existe");
        assertThat(problem(response).get("instance").asText()).isEqualTo(path);
        assertThat(output.getAll()).doesNotContain(MARKER);
        assertThat(linesAtLevel(output, "WARN")).singleElement().satisfies(line -> assertThat(line)
                .contains("Ruta no encontrada: GET")
                .contains("[" + correlationOf(response) + "]"));
    }

    // ── Autenticación y autorización ─────────────────────────────────────

    @ParameterizedTest
    @CsvSource({"appointment", "medical", "report", "statistics", "patients"})
    void withoutAValidTokenTheAnswerIsStillThe401OfTheEntryPoint(String probe) throws Exception {
        for (String token : new String[]{null, "invalido"}) {
            HttpResponse<String> response = get("/probe/" + probe + "/failure", token);

            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(response.body()).isEqualTo("{\"error\":\"no autorizado\"}");
        }
    }

    @ParameterizedTest
    @CsvSource({"appointment", "medical", "report", "statistics"})
    void aDenialInsideARethrowingModuleKeepsTheEmpty403(String probe) throws Exception {
        HttpResponse<String> response = get("/probe/" + probe + "/denied", "PATIENT");

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).isEmpty();
        assertThat(response.headers().firstValue("WWW-Authenticate")).isPresent();
    }

    @Test
    void aMethodLevelDenialOnARealAppointmentRouteKeepsTheEmpty403() throws Exception {
        HttpResponse<String> response =
                send("PUT", "/api/appointments/" + UUID.randomUUID() + "/mark-as-unassisted", "PATIENT", null);

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).isEmpty();
    }

    @Test
    void aDenialInPatientsKeepsTheStructured403() throws Exception {
        HttpResponse<String> direct = get("/probe/patients/denied", "PATIENT");
        HttpResponse<String> methodLevel = get(DOCUMENT_ROUTE, "PATIENT");

        for (HttpResponse<String> response : List.of(direct, methodLevel)) {
            assertProblem(response, 403, "ACCESS_DENIED", "security",
                    "No tiene permisos para acceder a este recurso.");
        }
    }
}
