package co.edu.unicauca.piedrazul.backend.config.security;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        final String securitySchemeName = "KeycloakToken";

        return new OpenAPI()
                .info(new Info()
                        .title("API Piedra Azul")
                        .version("1.0.0")
                        .description("Documentación oficial protegida por Keycloak."))
                // 1. Agrega el requerimiento de seguridad de forma global a todos los endpoints
                .addSecurityItem(new SecurityRequirement().addList(securitySchemeName))
                // 2. Define cómo se llama el esquema (Bearer Token / JWT)
                .components(new Components()
                        .addSecuritySchemes(securitySchemeName,
                                new SecurityScheme()
                                        .name(securitySchemeName)
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .description("Introduce tu token de acceso JWT obtenido de Keycloak")));
    }
}
