package co.edu.unicauca.piedrazul.backend.shared.audit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

    @Component
    @Order(1) // que corra lo antes posible en la cadena de filtros
    public class CorrelationIdFilter extends OncePerRequestFilter {

        private static final String HEADER = "X-Correlation-Id";
        private static final String MDC_KEY = "correlationId";

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain chain) throws ServletException, IOException {
            String correlationId = request.getHeader(HEADER);
            if (correlationId == null || correlationId.isBlank()) {
                correlationId = UUID.randomUUID().toString();
            }

            try {
                MDC.put(MDC_KEY, correlationId);
                response.setHeader(HEADER, correlationId);
                chain.doFilter(request, response);
            } finally {
                MDC.remove(MDC_KEY); // crítico: sin esto, se filtra a la siguiente request del mismo hilo
            }
        }
    }