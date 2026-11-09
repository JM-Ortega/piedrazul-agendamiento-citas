package co.edu.unicauca.piedrazul.backend.config.web;

import org.springframework.boot.actuate.autoconfigure.endpoint.condition.ConditionalOnAvailableEndpoint;
import org.springframework.boot.actuate.autoconfigure.endpoint.expose.EndpointExposure;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointProperties;
import org.springframework.boot.actuate.autoconfigure.web.server.ConditionalOnManagementPort;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementPortType;
import org.springframework.boot.actuate.endpoint.web.EndpointMapping;
import org.springframework.boot.actuate.endpoint.web.EndpointMediaTypes;
import org.springframework.boot.actuate.endpoint.web.ExposableWebEndpoint;
import org.springframework.boot.actuate.endpoint.web.WebEndpointsSupplier;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.webmvc.actuate.endpoint.web.AbstractWebMvcEndpointHandlerMapping;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collection;
import java.util.List;

/**
 * Mantiene {@code /actuator/health} en el puerto de la aplicación cuando el Actuator corre en su
 * propio listener: es el contrato del HEALTHCHECK de la imagen y de la verificación pública del
 * despliegue.
 *
 * <p>Es el mismo endpoint de Spring Boot (estado, 503 y visibilidad de detalles), publicado en una
 * segunda ruta. El {@code additional-path} de los grupos de salud no sirve aquí porque solo admite
 * un segmento. La autorización sigue siendo la de {@code SecurityConfig}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnManagementPort(ManagementPortType.DIFFERENT)
@ConditionalOnAvailableEndpoint(endpoint = HealthEndpoint.class, exposure = EndpointExposure.WEB)
public class ApplicationHealthEndpointConfig {

    @Bean
    ApplicationHealthHandlerMapping applicationHealthHandlerMapping(WebEndpointsSupplier endpoints,
                                                                    EndpointMediaTypes mediaTypes,
                                                                    WebEndpointProperties properties) {
        List<ExposableWebEndpoint> health = endpoints.getEndpoints().stream()
                .filter(endpoint -> endpoint.getEndpointId().equals(HealthEndpoint.ID))
                .toList();
        return new ApplicationHealthHandlerMapping(properties.getBasePath(), health, mediaTypes);
    }

    /**
     * No extiende {@code WebMvcEndpointHandlerMapping}: un bean de ese tipo aquí haría que el
     * contexto de gestión, hijo de este, no creara el suyo y se quedara sin endpoints.
     */
    static final class ApplicationHealthHandlerMapping extends AbstractWebMvcEndpointHandlerMapping {

        private ApplicationHealthHandlerMapping(String basePath, Collection<ExposableWebEndpoint> health,
                                                EndpointMediaTypes mediaTypes) {
            super(new EndpointMapping(basePath), health, mediaTypes, null, false);
            setOrder(-100);
        }

        @Override
        protected LinksHandler getLinksHandler() {
            return (request, response) -> null;
        }
    }
}
