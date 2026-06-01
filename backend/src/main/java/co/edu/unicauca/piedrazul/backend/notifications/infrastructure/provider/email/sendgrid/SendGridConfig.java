package co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.email.sendgrid;

import com.sendgrid.SendGrid;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(SendGridProperties.class)
@ConditionalOnProperty(
        prefix = "notifications.email.sendgrid",
        name = "enabled",
        havingValue = "true"
)
public class SendGridConfig {

    @Bean
    public SendGrid sendGrid(SendGridProperties properties) {
        return new SendGrid(properties.apiKey());
    }

    @Bean
    public SendGridEmailProvider sendGridEmailProvider(
            SendGrid sendGrid,
            SendGridProperties properties,
            CircuitBreakerRegistry circuitBreakerRegistry
    ) {
        return new SendGridEmailProvider(sendGrid, properties, circuitBreakerRegistry);
    }
}