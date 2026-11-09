package co.edu.unicauca.piedrazul.backend.shared.audit;

import co.edu.unicauca.piedrazul.backend.doctors.config.DoctorDataInitializer;
import co.edu.unicauca.piedrazul.backend.patients.infrastructure.persistence.PatientDataInitializer;
import co.edu.unicauca.piedrazul.backend.support.PostgresIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.AbstractFilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletContextInitializerBeans;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** El orden de filtros de la aplicación completa, con todos sus filtros registrados. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(CorrelationFullContextIT.TestSecurityBeans.class)
class CorrelationFullContextIT extends PostgresIntegrationSupport {

    private static final String HEADER = "X-Correlation-Id";
    private static final String VALID_ID = "3f2b8c1e-9a4d-4e6f-8b7a-1c2d3e4f5a6b";

    @TestConfiguration
    static class TestSecurityBeans {
        /** Evita resolver el issuer real de Keycloak al arrancar. */
        @Bean
        JwtDecoder jwtDecoder() {
            return token -> {
                throw new BadJwtException("token de prueba inválido");
            };
        }
    }

    // Siembran datos vía Keycloak real al arrancar.
    @MockitoBean
    private DoctorDataInitializer doctorDataInitializer;
    @MockitoBean
    private PatientDataInitializer patientDataInitializer;

    @LocalServerPort
    private int port;

    @Autowired
    private ListableBeanFactory beans;

    private final HttpClient client = HttpClient.newHttpClient();

    private HttpResponse<String> get(String path, String correlationId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header(HEADER, correlationId)
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private List<AbstractFilterRegistrationBean<?>> registrations() {
        List<AbstractFilterRegistrationBean<?>> registrations = new ArrayList<>();
        new ServletContextInitializerBeans(beans).stream()
                .filter(AbstractFilterRegistrationBean.class::isInstance)
                .forEach(registration -> registrations.add((AbstractFilterRegistrationBean<?>) registration));
        return registrations;
    }

    @Test
    void theFilterRunsAfterForwardedHeadersAndBeforeSpringSecurity() {
        assertThat(registrations())
                .extracting(AbstractFilterRegistrationBean::getFilterName)
                .containsSubsequence("forwardedHeaderFilter", "correlationIdFilter", "springSecurityFilterChain");
    }

    @Test
    void noOtherFilterSharesItsPriority() {
        assertThat(registrations())
                .filteredOn(registration -> registration.getOrder() == Ordered.HIGHEST_PRECEDENCE + 2)
                .extracting(AbstractFilterRegistrationBean::getFilterName)
                .containsExactly("correlationIdFilter");
    }

    @Test
    void aRequestStoppedBySecurityCarriesTheHeader() throws Exception {
        HttpResponse<String> response = get("/api/appointments", VALID_ID);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().allValues(HEADER)).containsExactly(VALID_ID);
    }

    @Test
    void aRequestThatPassesSecurityCarriesTheHeader() throws Exception {
        HttpResponse<String> response = get("/actuator/health", VALID_ID);

        assertThat(response.headers().allValues(HEADER)).containsExactly(VALID_ID);
    }

    @Test
    void anInternalPrefixIsReplaced() throws Exception {
        String rejected = "keycloak-session:" + VALID_ID;

        HttpResponse<String> response = get("/api/appointments", rejected);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().allValues(HEADER))
                .singleElement()
                .matches(value -> !value.equals(rejected) && value.length() == 36);
    }
}
