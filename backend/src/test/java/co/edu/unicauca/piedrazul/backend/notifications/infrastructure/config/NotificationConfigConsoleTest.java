package co.edu.unicauca.piedrazul.backend.notifications.infrastructure.config;

import co.edu.unicauca.piedrazul.backend.notifications.api.OtpDeliveryMode;
import co.edu.unicauca.piedrazul.backend.notifications.application.NotificationDispatcherService;
import co.edu.unicauca.piedrazul.backend.notifications.application.OtpDeliveryCapability;
import co.edu.unicauca.piedrazul.backend.notifications.application.ProviderRegistry;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.NotificationAttemptRepository;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.NotificationDispatchStore;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.NotificationRepository;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.NotificationScheduleRepository;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.console.ConsoleNotificationProvider;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.email.sendgrid.SendGridConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * El opt-in de diagnóstico ({@code notifications.console.show-sensitive-content}) llega al
 * dispatcher, que decide en cada despacho, y está apagado por defecto. Con la misma
 * configuración se resuelve la capacidad de envío de códigos.
 */
class NotificationConfigConsoleTest {

    @Configuration
    static class Repositories {
        @Bean
        NotificationRepository notificationRepository() {
            return mock(NotificationRepository.class);
        }

        @Bean
        NotificationScheduleRepository notificationScheduleRepository() {
            return mock(NotificationScheduleRepository.class);
        }

        @Bean
        NotificationAttemptRepository notificationAttemptRepository() {
            return mock(NotificationAttemptRepository.class);
        }

        @Bean
        NotificationDispatchStore notificationDispatchStore() {
            return mock(NotificationDispatchStore.class);
        }

        @Bean
        CircuitBreakerRegistry circuitBreakerRegistry() {
            return CircuitBreakerRegistry.ofDefaults();
        }

        // En la aplicación lo aporta la autoconfiguración de Bean Validation.
        @Bean
        LocalValidatorFactoryBean validator() {
            return new LocalValidatorFactoryBean();
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Repositories.class, NotificationConfig.class, SendGridConfig.class);

    @Test
    void diagnosticOptInIsOffByDefaultAndNoRealProviderIsRegistered() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(optIn(context.getBean(NotificationDispatcherService.class))).isFalse();
            assertThat(ReflectionTestUtils.getField(
                    context.getBean(ConsoleNotificationProvider.class), "showSensitiveContent")).isEqualTo(false);
            assertThat(context.getBean(ProviderRegistry.class).hasDeliveryProvider()).isFalse();
            assertThat(otpMode(context)).isEqualTo(OtpDeliveryMode.UNAVAILABLE);
        });
    }

    @Test
    void diagnosticOptInReachesDispatcher() {
        runner.withPropertyValues("notifications.console.show-sensitive-content=true")
                .run(context -> {
                    assertThat(optIn(context.getBean(NotificationDispatcherService.class))).isTrue();
                    assertThat(otpMode(context)).isEqualTo(OtpDeliveryMode.DIAGNOSTIC_CONSOLE);
                });
    }

    @Test
    void realProviderIsDetectedWhenSendGridIsEnabled() {
        runner.withPropertyValues(
                        "notifications.console.show-sensitive-content=true",
                        "notifications.email.sendgrid.enabled=true",
                        "notifications.email.sendgrid.api-key=synthetic-key")
                .run(context -> {
                    assertThat(context.getBean(ProviderRegistry.class).hasDeliveryProvider()).isTrue();
                    // SendGrid no es canal para códigos y además impide el diagnóstico por consola.
                    assertThat(otpMode(context)).isEqualTo(OtpDeliveryMode.UNAVAILABLE);
                });
    }

    private static OtpDeliveryMode otpMode(org.springframework.context.ApplicationContext context) {
        return context.getBean(OtpDeliveryCapability.class).mode();
    }

    private static boolean optIn(NotificationDispatcherService dispatcher) {
        return (boolean) ReflectionTestUtils.getField(dispatcher, "consoleSensitiveDiagnosticEnabled");
    }
}
