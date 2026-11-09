package co.edu.unicauca.piedrazul.backend.config.security;

import co.edu.unicauca.piedrazul.backend.config.web.CorsConfig;
import co.edu.unicauca.piedrazul.backend.shared.GlobalErrorAdvice;
import co.edu.unicauca.piedrazul.backend.shared.audit.CorrelationIdFilter;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
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
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.error.ErrorMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El id de correlación frente a la cadena de seguridad real, sobre Tomcat embebido.
 *
 * <p>El orden entre filtros lo decide el registro de Spring Boot, así que aquí corre {@link
 * SecurityConfig} tal cual con los filtros registrados por Boot; MockMvc con filtros añadidos a mano
 * no lo comprobaría. No hace falta base de datos: solo se levanta la parte web. Los tokens y el
 * controlador son sintéticos.
 */
@SpringBootTest(
        classes = CorrelationSecurityBoundaryTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class CorrelationSecurityBoundaryTest {

    private static final String HEADER = "X-Correlation-Id";
    private static final String VALID_ID = "3f2b8c1e-9a4d-4e6f-8b7a-1c2d3e4f5a6b";
    private static final String UNAUTHORIZED_BODY = "{\"error\":\"no autorizado\"}";
    private static final String HOSTILE_ID = "keycloak-session:hostil-valor-crudo";

    private static final Logger log = LoggerFactory.getLogger(CorrelationSecurityBoundaryTest.class);
    private static final List<String> MDC_SEEN_BY_HANDLER = new CopyOnWriteArrayList<>();

    @SpringBootConfiguration
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
            SecurityExceptionHandler.class,
            GlobalErrorAdvice.class,
            CorrelationIdFilter.class,
            ProbeController.class
    })
    static class Config {

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> {
                log.info("decodificando token de prueba");
                return switch (token) {
                    case "admin" -> jwtWithRole("ADMIN");
                    case "patient" -> jwtWithRole("PATIENT");
                    default -> throw new BadJwtException("token de prueba inválido");
                };
            };
        }

        /** Escribe en el log desde el despacho a /error, cuando la petición original ya salió de los filtros. */
        @Bean
        OncePerRequestFilter errorDispatchProbe() {
            return new OncePerRequestFilter() {
                @Override
                protected boolean shouldNotFilterErrorDispatch() {
                    return false;
                }

                @Override
                protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                                FilterChain chain) throws ServletException, IOException {
                    if (request.getDispatcherType() == DispatcherType.ERROR) {
                        log.info("traza en el despacho de error");
                    }
                    chain.doFilter(request, response);
                }
            };
        }

        private static Jwt jwtWithRole(String role) {
            return Jwt.withTokenValue("sintetico")
                    .header("alg", "none")
                    .subject(UUID.randomUUID().toString())
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(60))
                    .claim("realm_access", Map.of("roles", List.of(role)))
                    .build();
        }
    }

    /** Sin {@code @RestController}: no es un endpoint de la aplicación para EndpointAuthorizationTest. */
    @Controller
    @ResponseBody
    static class ProbeController {

        private static final class SyntheticConflict extends RuntimeException {
        }

        @GetMapping("/api/patients/document-types")
        String permitted() {
            return "publico";
        }

        @GetMapping("/probe/authenticated")
        String authenticated() {
            MDC_SEEN_BY_HANDLER.add(MDC.get("correlationId"));
            log.info("traza dentro de la peticion");
            return "autenticado";
        }

        @PreAuthorize("hasRole('ADMIN')")
        @GetMapping("/probe/admin")
        String admin() {
            return "admin";
        }

        @GetMapping("/probe/validated")
        ProblemDetail validated(@RequestParam int page, HttpServletResponse response) {
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "pagina " + page);
        }

        @GetMapping("/probe/conflict")
        String conflict() {
            throw new SyntheticConflict();
        }

        @GetMapping("/probe/send-error")
        void sendError(HttpServletResponse response) throws IOException {
            log.info("traza antes del despacho de error");
            response.sendError(HttpStatus.CONFLICT.value());
        }

        @GetMapping("/probe/failure")
        String failure() {
            throw new IllegalStateException("fallo sintetico");
        }

        @ExceptionHandler(SyntheticConflict.class)
        ProblemDetail handleConflict(HttpServletResponse response) {
            response.setStatus(HttpStatus.CONFLICT.value());
            return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "conflicto sintetico");
        }
    }

    @LocalServerPort
    private int port;

    @Autowired
    private ServletWebServerApplicationContext context;

    private final HttpClient client = HttpClient.newHttpClient();

    private HttpResponse<String> get(String path, String token, String correlationId) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (correlationId != null) {
            request.header(HEADER, correlationId);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String correlationOf(HttpResponse<String> response) {
        List<String> values = response.headers().allValues(HEADER);
        assertThat(values).hasSize(1);
        assertThat(UUID.fromString(values.getFirst()).toString()).isEqualTo(values.getFirst());
        return values.getFirst();
    }

    /** El orden y los despachos efectivos: los que quedaron registrados en Tomcat. */
    private FilterMap[] tomcatFilterMaps() {
        Tomcat tomcat = ((TomcatWebServer) context.getWebServer()).getTomcat();
        return ((Context) tomcat.getHost().findChildren()[0]).findFilterMaps();
    }

    @Test
    void theFilterRunsAfterForwardedHeadersAndBeforeSpringSecurity() {
        List<String> filters = Arrays.stream(tomcatFilterMaps()).map(FilterMap::getFilterName).toList();

        assertThat(filters).containsSubsequence(
                "forwardedHeaderFilter", CorrelationIdFilter.class.getName(), "springSecurityFilterChain");
    }

    @Test
    void theFilterIsRegisteredForTheErrorDispatch() {
        assertThat(tomcatFilterMaps())
                .filteredOn(map -> map.getFilterName().equals(CorrelationIdFilter.class.getName()))
                .singleElement()
                .satisfies(map -> assertThat(map.getDispatcherNames()).contains(DispatcherType.ERROR.name()));
    }

    @Test
    void aMissingTokenStaysA401AndCarriesTheHeader() throws Exception {
        HttpResponse<String> response = get("/probe/authenticated", null, null);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).isEqualTo(UNAUTHORIZED_BODY);
        correlationOf(response);
    }

    @Test
    void anInvalidTokenStaysA401AndCarriesTheHeader() throws Exception {
        HttpResponse<String> response = get("/probe/authenticated", "invalido", VALID_ID);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).isEqualTo(UNAUTHORIZED_BODY);
        assertThat(correlationOf(response)).isEqualTo(VALID_ID);
    }

    @Test
    void aUrlLevelDenialStaysA403AndCarriesTheHeader() throws Exception {
        HttpResponse<String> response = get("/actuator/env", "patient", VALID_ID);

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).isEmpty();
        assertThat(correlationOf(response)).isEqualTo(VALID_ID);
    }

    @Test
    void aMethodLevelDenialKeepsItsProblemAndCarriesTheHeader() throws Exception {
        HttpResponse<String> response = get("/probe/admin", "patient", VALID_ID);

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("\"errorCode\":\"ACCESS_DENIED\"");
        assertThat(correlationOf(response)).isEqualTo(VALID_ID);
    }

    @Test
    void aMethodLevelGrantStillReachesTheHandler() throws Exception {
        HttpResponse<String> response = get("/probe/admin", "admin", null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("admin");
        correlationOf(response);
    }

    @Test
    void aPublicRouteStillAnswersWithoutToken() throws Exception {
        HttpResponse<String> response = get("/api/patients/document-types", null, VALID_ID);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("publico");
        assertThat(correlationOf(response)).isEqualTo(VALID_ID);
    }

    @Test
    void aHandledBadRequestKeepsItsResponse() throws Exception {
        HttpResponse<String> response = get("/probe/validated?page=1", "admin", VALID_ID);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("pagina 1");
        assertThat(correlationOf(response)).isEqualTo(VALID_ID);
    }

    @Test
    void aRequestRejectedByTheFirewallIsA400ThroughTheErrorDispatch() throws Exception {
        HttpResponse<String> response = get("/probe/authenticated;x=1", "admin", VALID_ID);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(correlationOf(response)).isEqualTo(VALID_ID);
    }

    /**
     * ForwardedHeaderFilter corre primero y rechaza esto antes de que haya id, también en el despacho
     * a /error: esa respuesta no lleva el id, pero tampoco el valor crudo.
     */
    @Test
    void anInvalidForwardedHeaderIsStillRejectedWithoutEchoingTheRawId() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/probe/authenticated"))
                .header("X-Forwarded-Port", "no-es-un-puerto")
                .header(HEADER, HOSTILE_ID)
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.headers().map().toString()).doesNotContain(HOSTILE_ID);
        assertThat(response.body()).doesNotContain(HOSTILE_ID);
    }

    @Test
    void anUnknownRouteStaysA404() throws Exception {
        HttpResponse<String> response = get("/probe/no-existe", "admin", VALID_ID);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("\"errorCode\":\"RESOURCE_NOT_FOUND\"");
        assertThat(correlationOf(response)).isEqualTo(VALID_ID);
    }

    @Test
    void aHandledConflictKeepsItsResponse() throws Exception {
        HttpResponse<String> response = get("/probe/conflict", "admin", VALID_ID);

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(response.body()).contains("conflicto sintetico");
        assertThat(correlationOf(response)).isEqualTo(VALID_ID);
    }

    @Test
    void aContainerErrorResponseKeepsTheHeaderThroughTheErrorDispatch() throws Exception {
        HttpResponse<String> response = get("/probe/send-error", "admin", VALID_ID);

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(correlationOf(response)).isEqualTo(VALID_ID);
    }

    @Test
    void theErrorDispatchLogsTheIdSelectedForTheRequest(CapturedOutput output) throws Exception {
        HttpResponse<String> response = get("/probe/send-error", "admin", null);

        String id = correlationOf(response);
        assertThat(lineWith(output, "traza antes del despacho de error")).contains("[" + id + "] ");
        assertThat(lineWith(output, "traza en el despacho de error")).contains("[" + id + "] ");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "corr-1",
            "1-1-1-1-1",
            "3F2B8C1E-9A4D-4E6F-8B7A-1C2D3E4F5A6B",
            HOSTILE_ID
    })
    void aRejectedValueIsReplacedOnEveryKindOfResponse(String rejected, CapturedOutput output) throws Exception {
        for (HttpResponse<String> response : List.of(
                get("/probe/authenticated", null, rejected),
                get("/probe/authenticated", "invalido", rejected),
                get("/actuator/env", "patient", rejected),
                get("/probe/admin", "patient", rejected),
                get("/probe/authenticated", "admin", rejected))) {
            assertThat(correlationOf(response)).isNotEqualTo(rejected);
            assertThat(response.body()).doesNotContain(rejected);
        }

        assertThat(output.getAll()).doesNotContain(rejected);
    }

    @Test
    void anOversizedValueIsReplacedWithoutChangingTheResponse() throws Exception {
        String oversized = VALID_ID.repeat(20);

        HttpResponse<String> response = get("/probe/authenticated", null, oversized);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(correlationOf(response)).isNotEqualTo(oversized);
    }

    @Test
    void logsInsideARequestCarryTheSelectedId(CapturedOutput output) throws Exception {
        HttpResponse<String> response = get("/probe/authenticated", "admin", null);

        String id = correlationOf(response);
        assertThat(lineWith(output, "traza dentro de la peticion")).contains("[" + id + "] ");
        assertThat(MDC_SEEN_BY_HANDLER).contains(id);
    }

    @Test
    void logsEmittedInsideTheSecurityChainCarryTheSelectedId(CapturedOutput output) throws Exception {
        String id = UUID.randomUUID().toString();

        get("/probe/authenticated", "invalido", id);

        assertThat(output.getAll()).contains("[" + id + "] ");
        assertThat(lineWith(output, "decodificando token de prueba")).contains("[" + id + "] ");
    }

    @Test
    void anUnhandledErrorIsLoggedWithTheSelectedId(CapturedOutput output) throws Exception {
        String id = UUID.randomUUID().toString();

        HttpResponse<String> response = get("/probe/failure", "admin", id);

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body()).contains("\"errorCode\":\"INTERNAL_ERROR\"");
        assertThat(lineWith(output, "Excepción no controlada")).contains("[" + id + "] ");
    }

    @Test
    void logsOutsideARequestStayReadableWithoutInventingAnId(CapturedOutput output) throws Exception {
        Thread background = Thread.ofPlatform().name("tarea-de-fondo").start(
                () -> log.info("traza de fondo"));
        background.join();

        assertThat(lineWith(output, "traza de fondo"))
                .contains("tarea-de-fondo] [-] ")
                .doesNotContainPattern("[0-9a-f]{8}-[0-9a-f]{4}-");
    }

    private static String lineWith(CapturedOutput output, String text) {
        return output.getAll().lines()
                .filter(line -> line.contains(text))
                .reduce((first, last) -> last)
                .orElseThrow(() -> new AssertionError("no hay línea de log con: " + text));
    }
}
