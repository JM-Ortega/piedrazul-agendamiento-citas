package co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.email.ses;

import co.edu.unicauca.piedrazul.backend.notifications.application.RecipientEmailPolicy;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import jakarta.validation.Validator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Proveedor de correo por SES, solo si {@code notifications.email.ses.enabled=true}. Ningún bean
 * abre conexiones al arrancar, y el proveedor no aporta indicadores de salud: que SES no responda
 * no vuelve no saludable al backend.
 */
@Configuration
@ConditionalOnProperty(
        prefix = "notifications.email.ses",
        name = "enabled",
        havingValue = "true"
)
@EnableConfigurationProperties(SesSmtpProperties.class)
public class SesSmtpConfig {

    @Bean
    public CircuitBreaker sesSmtpCircuitBreaker(SesSmtpProperties properties) {
        SesSmtpProperties.CircuitBreakerSettings settings = properties.circuitBreaker();

        return CircuitBreaker.of(SesEmailProvider.PROVIDER_NAME, CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(settings.windowSize())
                .minimumNumberOfCalls(settings.minimumCalls())
                .failureRateThreshold(settings.failureRate())
                .waitDurationInOpenState(settings.openWait())
                .permittedNumberOfCallsInHalfOpenState(settings.halfOpenCalls())
                .build());
    }

    @Bean
    SesSmtpSender sesSmtpSender(SesSmtpProperties properties) {
        return new SesSmtpSender(properties, null);
    }

    @Bean
    public SesEmailProvider sesEmailProvider(
            SesSmtpSender sender,
            SesSmtpProperties properties,
            CircuitBreaker sesSmtpCircuitBreaker,
            Validator validator
    ) {
        return new SesEmailProvider(
                sender,
                properties,
                sesSmtpCircuitBreaker,
                new RecipientEmailPolicy(validator)
        );
    }
}
