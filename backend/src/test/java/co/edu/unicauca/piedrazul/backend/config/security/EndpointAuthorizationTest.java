package co.edu.unicauca.piedrazul.backend.config.security;

import co.edu.unicauca.piedrazul.backend.config.web.CorsConfig;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * Centinela de permisos: ningún endpoint queda abierto por olvido.
 *
 * <p>Cada endpoint declara quién puede llamarlo con {@code @PreAuthorize} (en el método o en el
 * controlador), o es uno de los públicos acordados en {@link #PUBLIC_ENDPOINTS}. Sin esto, un
 * endpoint nuevo sin anotación queda abierto a cualquier usuario autenticado, sea cual sea su rol,
 * porque la regla general de {@link SecurityConfig} es {@code anyRequest().authenticated()}.
 *
 * <p>Además se prueba la cadena de filtros real de {@link SecurityConfig}: sin token, todo lo que no
 * es público responde 401, y lo público no. Así la lista de este test y los {@code permitAll} de la
 * configuración no pueden separarse.
 */
@SpringJUnitWebConfig(EndpointAuthorizationTest.Config.class)
@TestPropertySource(properties = "app.cors.allowed-origins=http://localhost:4200")
class EndpointAuthorizationTest {

    private static final String BASE_PACKAGE = "co.edu.unicauca.piedrazul.backend";

    /** Los endpoints que se llaman sin iniciar sesión. Cambiar esta lista es una decisión de seguridad. */
    private static final Set<Endpoint> PUBLIC_ENDPOINTS = Set.of(
            new Endpoint(HttpMethod.POST, "/api/patients/with-user"),
            new Endpoint(HttpMethod.POST, "/api/patients/link-user-account/request-code"),
            new Endpoint(HttpMethod.POST, "/api/patients/link-user-account/confirm"),
            new Endpoint(HttpMethod.GET, "/api/patients/document/{documentNumber}/public"),
            new Endpoint(HttpMethod.GET, "/api/patients/document-types"),
            new Endpoint(HttpMethod.GET, "/api/patients/gender-types")
    );

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({SecurityConfig.class, JwtAuthConverter.class, CorsConfig.class})
    static class Config {
        /** Solo se prueban peticiones sin token: un token que llegue aquí es inválido. */
        @Bean
        JwtDecoder jwtDecoder() {
            return token -> {
                throw new BadJwtException("token de prueba inválido");
            };
        }
    }

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
                .build();
    }

    @Test
    void everyEndpointDeclaresWhoMayCallIt() {
        List<String> open = new ArrayList<>();
        for (Handler handler : handlers()) {
            if (!handler.restricted() && !PUBLIC_ENDPOINTS.contains(handler.endpoint())) {
                open.add(handler.describe());
            }
        }

        assertThat(open)
                .as("endpoints sin @PreAuthorize que no están en la lista de públicos: cualquier "
                        + "usuario autenticado podría llamarlos. Agrega @PreAuthorize o, si deben "
                        + "ser públicos, agrégalos a PUBLIC_ENDPOINTS y a SecurityConfig")
                .isEmpty();
    }

    @Test
    void publicEndpointsDoNotAlsoRequireARole() {
        List<String> contradictory = handlers().stream()
                .filter(handler -> handler.restricted() && PUBLIC_ENDPOINTS.contains(handler.endpoint()))
                .map(Handler::describe)
                .toList();

        assertThat(contradictory)
                .as("endpoints públicos con @PreAuthorize: nadie sin sesión podría usarlos")
                .isEmpty();
    }

    @Test
    void everyPublicEndpointStillExists() {
        Set<Endpoint> existing = new HashSet<>();
        handlers().forEach(handler -> existing.add(handler.endpoint()));

        assertThat(existing)
                .as("PUBLIC_ENDPOINTS nombra endpoints que ya no existen: quítalos de la lista y de SecurityConfig")
                .containsAll(PUBLIC_ENDPOINTS);
    }

    @Test
    void withoutATokenOnlyThePublicEndpointsLetTheRequestThrough() throws Exception {
        Set<String> rejectedPublic = new TreeSet<>();
        Set<String> acceptedPrivate = new TreeSet<>();

        for (Handler handler : handlers()) {
            int status = anonymous(handler.endpoint());
            boolean isPublic = PUBLIC_ENDPOINTS.contains(handler.endpoint());

            if (isPublic && status == 401) {
                rejectedPublic.add(handler.describe());
            }
            if (!isPublic && status != 401) {
                acceptedPrivate.add(handler.describe() + " → " + status);
            }
        }

        assertThat(acceptedPrivate)
                .as("endpoints privados que SecurityConfig deja pasar sin token")
                .isEmpty();
        assertThat(rejectedPublic)
                .as("endpoints públicos que SecurityConfig rechaza sin token: falta su permitAll")
                .isEmpty();
    }

    @Test
    void onlyTheHealthCheckOfTheActuatorIsPublic() throws Exception {
        assertThat(anonymous(new Endpoint(HttpMethod.GET, "/actuator/health"))).isNotEqualTo(401);
        assertThat(anonymous(new Endpoint(HttpMethod.GET, "/actuator/env"))).isEqualTo(401);
        assertThat(anonymous(new Endpoint(HttpMethod.GET, "/actuator/loggers"))).isEqualTo(401);
    }

    /** El estado de la respuesta sin token. Aquí no hay controladores: lo que pasa el filtro da 404. */
    private int anonymous(Endpoint endpoint) throws Exception {
        String uri = endpoint.path().replaceAll("\\{[^}]+}", "00000000-0000-0000-0000-000000000001");
        return mvc.perform(request(endpoint.method(), uri))
                .andReturn().getResponse().getStatus();
    }

    private static List<Handler> handlers() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        List<Handler> handlers = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(BASE_PACKAGE)) {
            Class<?> controller = ClassUtils.resolveClassName(definition.getBeanClassName(), null);
            RequestMapping base = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
            String[] basePaths = base == null || base.path().length == 0 ? new String[]{""} : base.path();
            boolean controllerRestricted = AnnotatedElementUtils.hasAnnotation(controller, PreAuthorize.class);

            for (Method method : controller.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null) continue;

                boolean restricted = controllerRestricted || AnnotatedElementUtils.hasAnnotation(method, PreAuthorize.class);
                String[] paths = mapping.path().length == 0 ? new String[]{""} : mapping.path();
                RequestMethod[] verbs = mapping.method().length == 0 ? new RequestMethod[]{RequestMethod.GET} : mapping.method();

                for (String basePath : basePaths) {
                    for (String path : paths) {
                        for (RequestMethod verb : verbs) {
                            Endpoint endpoint = new Endpoint(verb.asHttpMethod(), basePath + path);
                            handlers.add(new Handler(endpoint, controller.getSimpleName() + "." + method.getName(), restricted));
                        }
                    }
                }
            }
        }
        return handlers;
    }

    private record Endpoint(HttpMethod method, String path) {
    }

    private record Handler(Endpoint endpoint, String javaName, boolean restricted) {
        String describe() {
            return endpoint.method() + " " + endpoint.path() + " (" + javaName + ")";
        }
    }
}
