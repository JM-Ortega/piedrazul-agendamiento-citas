package co.edu.unicauca.piedrazul.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Configuración de seguridad para tests que permite omitir la validación de JWT.
 */
@Configuration
@Profile("test")
@EnableMethodSecurity
public class TestSecurityConfig {

    /**
     * SecurityFilterChain para tests que no valida JWT.
     * Permite que @WithMockUser funcione sin necesidad de Keycloak.
     */
    @Bean
    public SecurityFilterChain testSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(Customizer.withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/error").permitAll()
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**").permitAll()
                        .requestMatchers("/api/patients/document/*/public").permitAll()
                        .requestMatchers("/api/patients/with-user").permitAll()
                        .requestMatchers("/api/patients/link-user-account/request-code").permitAll()
                        .requestMatchers("/api/patients/link-user-account/confirm").permitAll()
                        .anyRequest().authenticated()
                )
                // Usar la seguridad básica de Spring en lugar de OAuth2 para tests
                .httpBasic(Customizer.withDefaults());

        return http.build();
    }
}
