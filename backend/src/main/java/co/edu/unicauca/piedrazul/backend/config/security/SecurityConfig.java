package co.edu.unicauca.piedrazul.backend.config.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.actuate.endpoint.web.WebServerNamespace;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AndRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.context.support.WebApplicationContextUtils;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthConverter jwtAuthConverter;

    public SecurityConfig(JwtAuthConverter jwtAuthConverter) {
        this.jwtAuthConverter = jwtAuthConverter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(Customizer.withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(prometheusOnManagementListener()).permitAll()
                        .requestMatchers("/actuator/health", "/error").permitAll()
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        // Solo tienen efecto con Springdoc activo (perfil dev, application.yaml); sin él no hay
                        // manejador y estas rutas no existen. Los dos literales cubren la especificación
                        // YAML y la redirección de la interfaz, que los patrones /** no alcanzan.
                        .requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/v3/api-docs.yaml",
                                "/swagger-ui.html", "/swagger-ui/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/patients/document/*/public").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/patients/with-user").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/patients/link-user-account/request-code").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/patients/link-user-account/confirm").permitAll()
                        .requestMatchers(HttpMethod.GET,"/api/patients/document-types").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/patients/gender-types").permitAll()
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthConverter))
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                            response.setContentType("application/json");
                            response.getWriter().write("{\"error\":\"no autorizado\"}");
                        })
                );

        return http.build();
    }

    /**
     * Prometheus raspa sin credenciales porque el listener de gestión no sale de la red privada. El
     * listener se comprueba aparte: {@code EndpointRequest} también coincidiría en el puerto público
     * si ambos llegaran a compartirlo, y entonces las métricas quedarían abiertas.
     */
    private static RequestMatcher prometheusOnManagementListener() {
        RequestMatcher managementListener = request -> WebServerApplicationContext.hasServerNamespace(
                WebApplicationContextUtils.findWebApplicationContext(request.getServletContext()),
                WebServerNamespace.MANAGEMENT.getValue());
        return new AndRequestMatcher(managementListener, EndpointRequest.to("prometheus"));
    }
}
