package co.edu.unicauca.piedrazul.backend.shared.audit;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** El id entrante solo se conserva si es un UUID canónico; cualquier otro se reemplaza. */
class CorrelationIdFilterTest {

    private static final String HEADER = "X-Correlation-Id";
    private static final String MDC_KEY = "correlationId";
    private static final String VALID_ID = "3f2b8c1e-9a4d-4e6f-8b7a-1c2d3e4f5a6b";

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    private static boolean isCanonicalUuid(String value) {
        return value != null && UUID.fromString(value).toString().equals(value);
    }

    @Test
    void withoutTheHeaderACanonicalIdIsGeneratedAndShared() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        List<String> seen = new ArrayList<>();

        filter.doFilter(new MockHttpServletRequest(), response, (req, res) -> seen.add(MDC.get(MDC_KEY)));

        assertThat(response.getHeader(HEADER)).matches(CorrelationIdFilterTest::isCanonicalUuid);
        assertThat(seen).containsExactly(response.getHeader(HEADER));
    }

    @Test
    void aCanonicalIdIsPreserved() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HEADER, VALID_ID);
        MockHttpServletResponse response = new MockHttpServletResponse();
        List<String> seen = new ArrayList<>();

        filter.doFilter(request, response, (req, res) -> seen.add(MDC.get(MDC_KEY)));

        assertThat(response.getHeader(HEADER)).isEqualTo(VALID_ID);
        assertThat(seen).containsExactly(VALID_ID);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "   ",
            "not-a-uuid",
            "corr-1",
            "1-1-1-1-1", // UUID.fromString la acepta
            "3F2B8C1E-9A4D-4E6F-8B7A-1C2D3E4F5A6B",
            "3f2b8c1e9a4d4e6f8b7a1c2d3e4f5a6b",
            "{3f2b8c1e-9a4d-4e6f-8b7a-1c2d3e4f5a6b}",
            "urn:uuid:3f2b8c1e-9a4d-4e6f-8b7a-1c2d3e4f5a6b",
            " 3f2b8c1e-9a4d-4e6f-8b7a-1c2d3e4f5a6b",
            "3f2b8c1e-9a4d-4e6f-8b7a-1c2d3e4f5a6b ",
            "3f2b8c1e-9a4d-4e6f-8b7a-1c2d3e4f5a6b\n",
            "3f2b8c1e-9a4d-4e6f-8b7a-1c2d3e4f5a6b\r\nX-Inyectado: si",
            "3f2b8c1e-9a4d-4e6f-8b7a-1c2d3e4f5a6b,3f2b8c1e-9a4d-4e6f-8b7a-1c2d3e4f5a6c",
            "3f2b8c1e-9a4d-4e6f-8b7a-1c2d3e4f5a6g",
            "keycloak-session:3f2b8c1e-9a4d-4e6f-8b7a-1c2d3e4f5a6b",
            "keycloak-event:3f2b8c1e-9a4d-4e6f-8b7a-1c2d3e4f5a6b",
            "keycloak-session:null",
            "<script>alert(1)</script>",
            "${jndi:ldap://example.invalid/x}",
    })
    void anyOtherValueIsReplacedAndNeverPropagated(String incoming) throws Exception {
        assertReplaced(incoming);
    }

    @Test
    void anOversizedValueIsReplacedAndNeverPropagated() throws Exception {
        assertReplaced(VALID_ID.repeat(100));
    }

    private void assertReplaced(String incoming) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HEADER, incoming);
        MockHttpServletResponse response = new MockHttpServletResponse();
        List<String> seen = new ArrayList<>();

        filter.doFilter(request, response, (req, res) -> {
            seen.add(MDC.get(MDC_KEY));
            res.getWriter().write("ok");
        });

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEqualTo("ok");
        assertThat(response.getHeader(HEADER))
                .isNotEqualTo(incoming)
                .matches(CorrelationIdFilterTest::isCanonicalUuid);
        assertThat(seen).containsExactly(response.getHeader(HEADER));
    }

    @Test
    void theMdcIsClearedAfterASuccessfulRequest() throws Exception {
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), (req, res) -> {
        });

        assertThat(MDC.get(MDC_KEY)).isNull();
    }

    @Test
    void theMdcIsClearedWhenTheChainFails() {
        FilterChain failing = (req, res) -> {
            throw new ServletException("fallo de prueba");
        };

        assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), failing))
                .isInstanceOf(ServletException.class);

        assertThat(MDC.get(MDC_KEY)).isNull();
    }

    @Test
    void theMdcIsClearedWhenTheChainFailsWithAnUncheckedError() {
        FilterChain failing = (req, res) -> {
            throw new IllegalStateException("fallo de prueba");
        };

        assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), failing))
                .isInstanceOf(IllegalStateException.class);

        assertThat(MDC.get(MDC_KEY)).isNull();
    }

    @Test
    void aRequestOnTheSameThreadDoesNotInheritTheIdOfThePreviousOne() throws Exception {
        MockHttpServletRequest first = new MockHttpServletRequest();
        first.addHeader(HEADER, VALID_ID);
        List<String> seen = new ArrayList<>();
        FilterChain recording = (req, res) -> seen.add(MDC.get(MDC_KEY));

        filter.doFilter(first, new MockHttpServletResponse(), recording);
        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest(), secondResponse, recording);

        assertThat(seen).hasSize(2);
        assertThat(seen.get(1)).isNotEqualTo(VALID_ID).isEqualTo(secondResponse.getHeader(HEADER));
    }

    @Test
    void theErrorDispatchKeepsTheIdSelectedForTheRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {
        });
        String selected = response.getHeader(HEADER);

        request.setDispatcherType(DispatcherType.ERROR);
        MockHttpServletResponse errorResponse = new MockHttpServletResponse();
        List<String> seen = new ArrayList<>();
        filter.doFilter(request, errorResponse, (req, res) -> seen.add(MDC.get(MDC_KEY)));

        assertThat(seen).containsExactly(selected);
        assertThat(errorResponse.getHeader(HEADER)).isEqualTo(selected);
        assertThat(MDC.get(MDC_KEY)).isNull();
    }
}
