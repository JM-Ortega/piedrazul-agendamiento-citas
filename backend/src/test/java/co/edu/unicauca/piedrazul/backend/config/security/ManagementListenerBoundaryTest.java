package co.edu.unicauca.piedrazul.backend.config.security;

import co.edu.unicauca.piedrazul.backend.config.web.ApplicationHealthEndpointConfig;
import co.edu.unicauca.piedrazul.backend.config.web.CorsConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.actuate.autoconfigure.endpoint.EndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.endpoint.jackson.JacksonEndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.info.InfoEndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementContextAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.health.autoconfigure.actuate.endpoint.HealthEndpointAutoConfiguration;
import org.springframework.boot.health.autoconfigure.contributor.HealthContributorAutoConfiguration;
import org.springframework.boot.health.autoconfigure.registry.HealthContributorRegistryAutoConfiguration;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.prometheus.PrometheusMetricsExportAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.jvm.JvmMetricsAutoConfiguration;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.servlet.autoconfigure.HttpEncodingAutoConfiguration;
import org.springframework.boot.servlet.autoconfigure.actuate.web.ServletManagementContextAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.tomcat.autoconfigure.actuate.web.server.TomcatServletManagementContextAutoConfiguration;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcObservationAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.actuate.endpoint.web.WebMvcHealthEndpointExtensionAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.error.ErrorMvcAutoConfiguration;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Frontera entre el listener de la aplicación y el de gestión, sobre Tomcat embebido real.
 *
 * <p>Con MockMvc no existen dos listeners ni el contexto de gestión, que es justo lo que se prueba:
 * aquí corren los dos servidores y {@link SecurityConfig} tal cual. No hace falta base de datos; el
 * indicador de salud y los tokens son sintéticos.
 */
class ManagementListenerBoundaryTest {

    private static final AtomicBoolean HEALTHY = new AtomicBoolean(true);
    private static final String UNAUTHORIZED_BODY = "{\"error\":\"no autorizado\"}";

    // Esta clase externa no tiene anotaciones ni métodos de prueba propios (todo está en los
    // @Nested), así que el filtro de tests de Boot no la reconoce y el escaneo de
    // BackendApplication registraría Config en los contextos completos. @TestComponent la excluye
    // de ese escaneo sin volverla @TestConfiguration, que haría buscar BackendApplication.
    @SpringBootConfiguration
    @TestComponent
    @ImportAutoConfiguration({
            PropertyPlaceholderAutoConfiguration.class,
            TomcatServletWebServerAutoConfiguration.class,
            TomcatServletManagementContextAutoConfiguration.class,
            ServletManagementContextAutoConfiguration.class,
            ManagementContextAutoConfiguration.class,
            DispatcherServletAutoConfiguration.class,
            HttpEncodingAutoConfiguration.class,
            HttpMessageConvertersAutoConfiguration.class,
            JacksonAutoConfiguration.class,
            JacksonEndpointAutoConfiguration.class,
            WebMvcAutoConfiguration.class,
            ErrorMvcAutoConfiguration.class,
            SecurityAutoConfiguration.class,
            ServletWebSecurityAutoConfiguration.class,
            SecurityFilterAutoConfiguration.class,
            EndpointAutoConfiguration.class,
            WebEndpointAutoConfiguration.class,
            HealthContributorRegistryAutoConfiguration.class,
            HealthContributorAutoConfiguration.class,
            HealthEndpointAutoConfiguration.class,
            WebMvcHealthEndpointExtensionAutoConfiguration.class,
            MetricsAutoConfiguration.class,
            CompositeMeterRegistryAutoConfiguration.class,
            PrometheusMetricsExportAutoConfiguration.class,
            JvmMetricsAutoConfiguration.class,
            ObservationAutoConfiguration.class,
            WebMvcObservationAutoConfiguration.class,
            InfoEndpointAutoConfiguration.class
    })
    @Import({
            SecurityConfig.class,
            JwtAuthConverter.class,
            CorsConfig.class,
            SecurityExceptionHandler.class,
            ApplicationHealthEndpointConfig.class
    })
    static class Config {

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> switch (token) {
                case "admin" -> jwtWithRole("ADMIN");
                case "patient" -> jwtWithRole("PATIENT");
                default -> throw new BadJwtException("token de prueba inválido");
            };
        }

        @Bean
        HealthIndicator controlledDatabase() {
            return () -> HEALTHY.get()
                    ? Health.up().withDetail("database", "sintetica").build()
                    : Health.down().withDetail("database", "sintetica").build();
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

    @Nested
    @SpringBootTest(
            classes = Config.class,
            webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = {
                    "app.cors.allowed-origins=http://localhost:4200",
                    "server.forward-headers-strategy=framework",
                    "management.server.port=0",
                    "management.endpoints.web.exposure.include=health,info,prometheus",
                    "management.endpoint.health.show-details=when-authorized",
                    "management.metrics.distribution.slo.http.server.requests=50ms,1s"
            })
    class SeparateListeners {

        @LocalServerPort
        private int applicationPort;

        @LocalManagementPort
        private int managementPort;

        @AfterEach
        void restoreHealth() {
            HEALTHY.set(true);
        }

        @Test
        void theListenersAreDistinct() {
            assertThat(managementPort).isNotEqualTo(applicationPort);
        }

        @Test
        void anonymousHealthOnTheApplicationPortKeepsPathStatusAndHidesDetails() throws Exception {
            HttpResponse<String> response = get(applicationPort, "/actuator/health", null);

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo("{\"status\":\"UP\"}");
        }

        @Test
        void anUnavailableDependencyAnswers503WithoutDetails() throws Exception {
            HEALTHY.set(false);

            HttpResponse<String> response = get(applicationPort, "/actuator/health", null);

            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(response.body()).isEqualTo("{\"status\":\"DOWN\"}");
        }

        @Test
        void anAuthorizedPrincipalStillSeesTheDetailsOnTheApplicationPort() throws Exception {
            HttpResponse<String> response = get(applicationPort, "/actuator/health", "admin");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("\"controlledDatabase\"", "\"database\":\"sintetica\"");
        }

        @Test
        void healthSubpathsStayRestrictedOnTheApplicationPort() throws Exception {
            assertThat(get(applicationPort, "/actuator/health/controlledDatabase", null).statusCode())
                    .isEqualTo(401);
            assertThat(get(applicationPort, "/actuator/health/controlledDatabase", "patient").statusCode())
                    .isEqualTo(403);
            assertThat(get(applicationPort, "/actuator/health/controlledDatabase", "admin").statusCode())
                    .isEqualTo(200);
        }

        @Test
        void prometheusIsNotServedOnTheApplicationPortToAnyone() throws Exception {
            HttpResponse<String> anonymous = get(applicationPort, "/actuator/prometheus", null);

            assertThat(anonymous.statusCode()).isEqualTo(401);
            assertThat(anonymous.body()).isEqualTo(UNAUTHORIZED_BODY);
            assertThat(get(applicationPort, "/actuator/prometheus", "patient").statusCode()).isEqualTo(403);
            assertThat(get(applicationPort, "/actuator/prometheus", "admin").statusCode()).isEqualTo(404);
        }

        @ParameterizedTest
        @ValueSource(strings = {"/actuator/prometheus/", "/actuator//prometheus", "/actuator/./prometheus",
                "/actuator/prometheus;x=1", "/actuator/health/../prometheus", "/metrics", "/prometheus"})
        void pathVariantsDoNotReachPrometheusOnTheApplicationPort(String path) throws Exception {
            HttpResponse<String> response = get(applicationPort, path, null);

            assertThat(response.statusCode()).isIn(400, 401);
            assertThat(response.body()).doesNotContain("jvm_memory");
        }

        @Test
        void forwardedHeadersDoNotImpersonateTheManagementListener() throws Exception {
            HttpResponse<String> response = send(HttpRequest.newBuilder(uri(applicationPort, "/actuator/prometheus"))
                    .header("X-Forwarded-Port", String.valueOf(managementPort))
                    .header("X-Forwarded-Host", "localhost:" + managementPort)
                    .build());

            assertThat(response.statusCode()).isEqualTo(401);
        }

        @Test
        void prometheusAnswersAnonymouslyOnTheManagementPort() throws Exception {
            HttpResponse<String> response = get(managementPort, "/actuator/prometheus", null);

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("text/plain");
            assertThat(response.body()).contains("# TYPE jvm_memory_used_bytes gauge");
        }

        @Test
        void applicationRequestsAreMeasuredWithTheRoutePatternOrWithoutARoute() throws Exception {
            get(applicationPort, "/actuator/health", null);
            get(applicationPort, "/ruta/sensible/1234567890?token=secreto", null);

            String metrics = get(managementPort, "/actuator/prometheus", null).body();

            assertThat(metrics)
                    .contains("uri=\"/actuator/health\"")
                    .contains("http_server_requests_seconds_bucket")
                    .doesNotContain("1234567890", "secreto", "/ruta/sensible");
        }

        @Test
        void healthOnTheManagementPortStaysPublicAndWithoutDetails() throws Exception {
            HttpResponse<String> response = get(managementPort, "/actuator/health", null);

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo("{\"status\":\"UP\"}");
        }

        @Test
        void otherActuatorEndpointsKeepTheirRestrictionsOnTheManagementPort() throws Exception {
            assertThat(get(managementPort, "/actuator/info", null).statusCode()).isEqualTo(401);
            assertThat(get(managementPort, "/actuator/info", "patient").statusCode()).isEqualTo(403);
            assertThat(get(managementPort, "/actuator/info", "admin").statusCode()).isEqualTo(200);
            assertThat(get(managementPort, "/actuator", null).statusCode()).isEqualTo(401);
            assertThat(get(managementPort, "/actuator/env", null).statusCode()).isEqualTo(401);
            assertThat(get(managementPort, "/actuator/env", "admin").statusCode()).isEqualTo(404);
            assertThat(get(managementPort, "/actuator/metrics", "admin").statusCode()).isEqualTo(404);
            assertThat(get(managementPort, "/api/patients/document-types", null).statusCode()).isIn(200, 404);
        }
    }

    /** Un puerto de gestión mal configurado no puede dejar las métricas en el listener público. */
    @Nested
    @SpringBootTest(
            classes = Config.class,
            webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = {
                    "app.cors.allowed-origins=http://localhost:4200",
                    // Vacío anula el 8081 de application.yaml: el Actuator comparte el listener
                    "management.server.port=",
                    "management.endpoints.web.exposure.include=health,info,prometheus"
            })
    class SharedListener {

        @LocalServerPort
        private int port;

        @Test
        void prometheusFailsClosedWhenThereIsNoManagementListener() throws Exception {
            assertThat(get(port, "/actuator/prometheus", null).statusCode()).isEqualTo(401);
            assertThat(get(port, "/actuator/prometheus", "patient").statusCode()).isEqualTo(403);
            // El endpoint sí está en este listener: el 401 viene de la autorización, no de su ausencia
            assertThat(get(port, "/actuator/prometheus", "admin").statusCode()).isEqualTo(200);
        }

        @Test
        void healthKeepsItsPath() throws Exception {
            assertThat(get(port, "/actuator/health", null).statusCode()).isEqualTo(200);
        }
    }

    private static HttpResponse<String> get(int port, String path, String token) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(port, path));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return send(request.build());
    }

    private static URI uri(int port, String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}
