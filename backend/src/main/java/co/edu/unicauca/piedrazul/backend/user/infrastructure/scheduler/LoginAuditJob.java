package co.edu.unicauca.piedrazul.backend.user.infrastructure.scheduler;

import co.edu.unicauca.piedrazul.backend.user.application.LoginAuditCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Recoge periódicamente de Keycloak los intentos de inicio de sesión del personal. */
@Component
@ConditionalOnProperty(prefix = "audit.login-events", name = "enabled", havingValue = "true", matchIfMissing = true)
public class LoginAuditJob {

    private static final Logger log = LoggerFactory.getLogger(LoginAuditJob.class);

    private final LoginAuditCollector collector;

    public LoginAuditJob(LoginAuditCollector collector) {
        this.collector = collector;
    }

    @Scheduled(
            initialDelayString = "${audit.login-events.initial-delay:60000}",
            fixedDelayString = "${audit.login-events.fixed-delay:120000}"
    )
    public void collectLoginAttempts() {
        try {
            collector.collect();
        } catch (RuntimeException e) {
            // Sin el rol view-events o con Keycloak caído no se avanza: se reintenta en la próxima ejecución.
            log.warn("No se pudieron leer los inicios de sesión de Keycloak: {}", e.getMessage());
        }
    }
}
