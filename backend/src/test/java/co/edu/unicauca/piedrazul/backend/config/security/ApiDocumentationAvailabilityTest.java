package co.edu.unicauca.piedrazul.backend.config.security;

import co.edu.unicauca.piedrazul.backend.config.web.CorsConfig;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Disponibilidad de Swagger UI y de la especificación OpenAPI según el perfil.
 *
 * <p>Se carga el {@code application.yaml} real, la configuración real de Springdoc y la cadena de
 * {@link SecurityConfig} tal cual; no hay base de datos ni servicios externos. La ausencia se
 * comprueba en dos niveles distintos: los beans y mapeos de Springdoc no existen, y una petición
 * autenticada (que supera la cadena de seguridad) no recibe documentación. Un 401 anónimo no
 * demuestra ausencia, porque la regla general de la cadena rechaza cualquier ruta desconocida.
 */
class ApiDocumentationAvailabilityTest {

    private static final String DOCS_BEAN_TYPE = "org.springdoc.webmvc.api.OpenApiWebMvcResource";
    private static final String UI_BEAN_TYPE = "org.springdoc.webmvc.ui.SwaggerWelcomeWebMvc";

    private static final List<String> SPEC_ROUTES = List.of(
            "/v3/api-docs", "/v3/api-docs/swagger-config", "/v3/api-docs.yaml");
    private static final List<String> UI_ROUTES = List.of(
            "/swagger-ui.html", "/swagger-ui/index.html");

    @SpringBootConfiguration
    @TestComponent
    @ImportAutoConfiguration({
            PropertyPlaceholderAutoConfiguration.class,
            DispatcherServletAutoConfiguration.class,
            HttpMessageConvertersAutoConfiguration.class,
            JacksonAutoConfiguration.class,
            WebMvcAutoConfiguration.class,
            SecurityAutoConfiguration.class,
            ServletWebSecurityAutoConfiguration.class,
            SecurityFilterAutoConfiguration.class,
            org.springdoc.core.configuration.SpringDocConfiguration.class,
            org.springdoc.core.properties.SpringDocConfigProperties.class,
            org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration.class,
            org.springdoc.webmvc.core.configuration.MultipleOpenApiSupportConfiguration.class,
            org.springdoc.webmvc.ui.SwaggerConfig.class,
            org.springdoc.core.properties.SwaggerUiConfigProperties.class,
            org.springdoc.core.properties.SwaggerUiOAuthProperties.class
    })
    @Import({SecurityConfig.class, JwtAuthConverter.class, CorsConfig.class, OpenApiConfig.class,
            ProbeController.class})
    static class Config {

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> {
                if (!"patient".equals(token)) {
                    throw new BadJwtException("token de prueba inválido");
                }
                return Jwt.withTokenValue("sintetico")
                        .header("alg", "none")
                        .subject(UUID.randomUUID().toString())
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(60))
                        .claim("realm_access", Map.of("roles", List.of("PATIENT")))
                        .build();
            };
        }
    }

    /**
     * Ruta de control: demuestra que un token válido sí supera la cadena en cada perfil. Sin
     * {@code @RestController}: no es un endpoint de la aplicación para EndpointAuthorizationTest.
     */
    @Controller
    @ResponseBody
    static class ProbeController {
        @GetMapping("/probe/ok")
        String ok() {
            return "ok";
        }
    }

    abstract static class Base {

        @Autowired
        WebApplicationContext context;

        MockMvc mvc;

        @BeforeEach
        void setUp() {
            mvc = MockMvcBuilders.webAppContextSetup(context)
                    .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
                    .build();
        }

        MvcResult call(String path, String token) throws Exception {
            var request = get(path).accept(MediaType.ALL);
            if (token != null) {
                request.header("Authorization", "Bearer " + token);
            }
            return mvc.perform(request).andReturn();
        }

        boolean hasBean(String type) throws ClassNotFoundException {
            return context.getBeanNamesForType(Class.forName(type)).length > 0;
        }

        boolean hasDocumentationMapping() {
            return context.getBean(RequestMappingHandlerMapping.class).getHandlerMethods().keySet().stream()
                    .flatMap(info -> info.getPathPatternsCondition().getPatternValues().stream())
                    .anyMatch(path -> path.startsWith("/v3/api-docs") || path.startsWith("/swagger-ui"));
        }
    }

    abstract static class Disabled extends Base {

        @Test
        void springdocBeansAndMappingsAreAbsent() throws Exception {
            assertThat(hasBean(DOCS_BEAN_TYPE)).isFalse();
            assertThat(hasBean(UI_BEAN_TYPE)).isFalse();
            assertThat(hasDocumentationMapping()).isFalse();
        }

        @Test
        void aValidTokenReachesTheApiButNoDocumentation() throws Exception {
            assertThat(call("/probe/ok", "patient").getResponse().getStatus()).isEqualTo(200);

            for (String path : allRoutes()) {
                var response = call(path, "patient").getResponse();
                // Con token válido la cadena de seguridad no interviene: lo que responde es
                // el MVC, y sin Springdoc no hay manejador para la ruta.
                assertThat(response.getStatus()).as(path).isEqualTo(404);
                assertThat(response.getContentAsString()).as(path)
                        .doesNotContain("openapi", "swagger-ui", "API Piedra Azul");
            }
        }

        @Test
        void anonymousRequestsNeverGetDocumentation() throws Exception {
            for (String path : allRoutes()) {
                var response = call(path, null).getResponse();
                assertThat(response.getStatus()).as(path).isGreaterThanOrEqualTo(400);
                assertThat(response.getContentAsString()).as(path)
                        .doesNotContain("openapi", "swagger-ui", "API Piedra Azul");
            }
        }

        private static List<String> allRoutes() {
            return java.util.stream.Stream.concat(SPEC_ROUTES.stream(), UI_ROUTES.stream()).toList();
        }
    }

    @Nested
    @SpringBootTest(classes = Config.class)
    class NoProfile extends Disabled {
    }

    @Nested
    @SpringBootTest(classes = Config.class)
    @ActiveProfiles("prod")
    class Prod extends Disabled {
    }

    @Nested
    @SpringBootTest(classes = Config.class)
    @ActiveProfiles({"prod", "dev"})
    class ProdThenDev extends Disabled {
    }

    @Nested
    @SpringBootTest(classes = Config.class)
    @ActiveProfiles({"dev", "prod"})
    class DevThenProd extends Disabled {
    }

    @Nested
    @SpringBootTest(classes = Config.class)
    @ActiveProfiles("unknown")
    class UnknownProfile extends Disabled {
    }

    @Nested
    @SpringBootTest(classes = Config.class)
    @ActiveProfiles("dev")
    class Dev extends Base {

        @Test
        void springdocIsRegistered() throws Exception {
            assertThat(hasBean(DOCS_BEAN_TYPE)).isTrue();
            assertThat(hasBean(UI_BEAN_TYPE)).isTrue();
            assertThat(hasDocumentationMapping()).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {"/v3/api-docs", "/v3/api-docs/swagger-config", "/v3/api-docs.yaml"})
        void specificationRoutesServeDocumentationAnonymously(String path) throws Exception {
            var response = call(path, null).getResponse();

            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getContentAsString()).contains(path.endsWith("swagger-config") ? "url" : "API Piedra Azul");
        }

        @Test
        void theSpecificationDescribesTheApi() throws Exception {
            var body = call("/v3/api-docs", null).getResponse().getContentAsString();

            assertThat(body).contains("\"openapi\"").contains("KeycloakToken").contains("/probe/ok");
        }

        @Test
        void swaggerUiEntryPointsAreReachableAnonymously() throws Exception {
            var index = call("/swagger-ui/index.html", null).getResponse();
            assertThat(index.getStatus()).isEqualTo(200);

            var redirect = call("/swagger-ui.html", null).getResponse();
            assertThat(redirect.getStatus()).isIn(301, 302, 303, 307, 308);
            assertThat(redirect.getHeader("Location")).contains("/swagger-ui/index.html");
        }

        @Test
        void aRouteOutsideTheApiStillRequiresAuthentication() throws Exception {
            assertThat(call("/probe/ok", null).getResponse().getStatus()).isEqualTo(401);
            assertThat(call("/probe/ok", "patient").getResponse().getStatus()).isEqualTo(200);
        }
    }
}
