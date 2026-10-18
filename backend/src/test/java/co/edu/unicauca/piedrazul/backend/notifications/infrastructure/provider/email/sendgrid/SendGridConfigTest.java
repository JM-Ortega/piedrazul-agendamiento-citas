package co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.email.sendgrid;

import com.sendgrid.SendGrid;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Arranque con SendGrid deshabilitado (producción) y habilitado. Las propiedades se enlazan
 * siempre, como en NotificationConfig, aunque SendGridConfig no se cargue.
 */
class SendGridConfigTest {

    @Configuration
    @EnableConfigurationProperties(SendGridProperties.class)
    static class AlwaysBoundProperties {
        @Bean
        CircuitBreakerRegistry circuitBreakerRegistry() {
            return CircuitBreakerRegistry.ofDefaults();
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AlwaysBoundProperties.class, SendGridConfig.class);

    @Test
    void disabledStartsWithoutApiKeyAndRegistersNoProvider() {
        // El .env de producción deja SENDGRID_API_KEY vacía con el proveedor deshabilitado
        runner.withPropertyValues(
                        "notifications.email.sendgrid.enabled=false",
                        "notifications.email.sendgrid.api-key=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(SendGrid.class);
                    assertThat(context).doesNotHaveBean(SendGridEmailProvider.class);
                });
    }

    @Test
    void disabledStartsWhenApiKeyIsAbsent() {
        runner.withPropertyValues("notifications.email.sendgrid.enabled=false")
                .run(context -> assertThat(context).hasNotFailed()
                        .doesNotHaveBean(SendGridEmailProvider.class));
    }

    @Test
    void enabledWithBlankApiKeyFailsStartup() {
        runner.withPropertyValues(
                        "notifications.email.sendgrid.enabled=true",
                        "notifications.email.sendgrid.api-key=  ")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("SENDGRID_API_KEY es obligatorio"));
    }

    @Test
    void enabledWithoutApiKeyFailsStartup() {
        runner.withPropertyValues("notifications.email.sendgrid.enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void enabledWithApiKeyRegistersProvider() {
        runner.withPropertyValues(
                        "notifications.email.sendgrid.enabled=true",
                        "notifications.email.sendgrid.api-key=synthetic-key")
                .run(context -> assertThat(context).hasNotFailed()
                        .hasSingleBean(SendGridEmailProvider.class));
    }
}
