package co.edu.unicauca.piedrazul.backend.shared.audit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Da a cada petición HTTP un id de correlación seguro y lo deja en el MDC y en la respuesta.
 *
 * <p>El id entrante viene del cliente y no es de confianza: solo se conserva si es un UUID en forma
 * canónica. Cualquier otro valor se descarta, sin rechazar la petición ni escribirlo en ningún lado,
 * y se genera uno nuevo. Así un cliente tampoco puede hacerse pasar por los identificadores internos
 * de la auditoría ({@code keycloak-session:*}, {@code keycloak-event:*}).
 *
 * <p>Corre antes de Spring Security: los 401 y 403 de la cadena de seguridad no llegan a los filtros
 * que van después. La prioridad es la primera libre: {@code ForwardedHeaderFilter} usa
 * {@code HIGHEST_PRECEDENCE} y el filtro de observación de Spring Boot, {@code HIGHEST_PRECEDENCE + 1}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class CorrelationIdFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-Correlation-Id";
    private static final String MDC_KEY = "correlationId";
    private static final String REQUEST_ATTRIBUTE = CorrelationIdFilter.class.getName() + ".id";

    // Con expresión propia y no UUID.fromString, que acepta formas no canónicas como "1-1-1-1-1".
    private static final Pattern CANONICAL_UUID =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    /** El despacho a /error también escribe en el log y debe conservar el id de la petición. */
    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String correlationId = resolve(request);

        try {
            MDC.put(MDC_KEY, correlationId);
            response.setHeader(HEADER, correlationId);
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY); // crítico: sin esto, se filtra a la siguiente request del mismo hilo
        }
    }

    private static String resolve(HttpServletRequest request) {
        if (request.getAttribute(REQUEST_ATTRIBUTE) instanceof String selected) {
            return selected;
        }

        String incoming = request.getHeader(HEADER);
        String selected = incoming != null && CANONICAL_UUID.matcher(incoming).matches()
                ? incoming
                : UUID.randomUUID().toString();
        request.setAttribute(REQUEST_ATTRIBUTE, selected);
        return selected;
    }
}
