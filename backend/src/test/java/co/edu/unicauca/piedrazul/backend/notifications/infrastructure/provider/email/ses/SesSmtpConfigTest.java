package co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.email.ses;

import co.edu.unicauca.piedrazul.backend.appointment.eligibility.AppointmentEligibility;
import co.edu.unicauca.piedrazul.backend.notifications.api.OtpDeliveryMode;
import co.edu.unicauca.piedrazul.backend.notifications.application.OtpDeliveryCapability;
import co.edu.unicauca.piedrazul.backend.notifications.application.ProviderRegistry;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationChannel;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.NotificationAttemptRepository;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.NotificationDispatchStore;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.NotificationProvider;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.NotificationRepository;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.NotificationScheduleRepository;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.config.NotificationConfig;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.console.ConsoleNotificationProvider;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.ClassUtils;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.net.ServerSocket;
import java.time.Duration;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Arranque con SES deshabilitado (el valor por defecto) y habilitado, en un contexto de Spring con
 * la configuración real. Deshabilitado no exige nada; habilitado sin completar no arranca y no
 * revela secretos; en ningún caso se contacta con el servidor al arrancar.
 */
class SesSmtpConfigTest {

    private static final String SYNTHETIC_PASSWORD = "synthetic-smtp-password-value";

    private static final String[] COMPLETE = {
            "notifications.email.ses.enabled=true",
            "notifications.email.ses.host=smtp.invalid",
            "notifications.email.ses.port=587",
            "notifications.email.ses.username=SYNTHETICUSER",
            "notifications.email.ses.password=" + SYNTHETIC_PASSWORD,
            "notifications.email.ses.from-email=no-reply@piedrazul.example"
    };

    @Configuration
    static class Collaborators {
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
        AppointmentEligibility appointmentEligibility() {
            return mock(AppointmentEligibility.class);
        }

        @Bean
        LocalValidatorFactoryBean validator() {
            return new LocalValidatorFactoryBean();
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Collaborators.class, NotificationConfig.class, SesSmtpConfig.class);

    // ── Deshabilitado ───────────────────────────────────────────────────

    @Test
    void disabledByDefaultItStartsWithoutAnyPropertyAndRegistersNothing() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(SesEmailProvider.class);
            assertThat(context).doesNotHaveBean(SesSmtpSender.class);
            assertThat(context).doesNotHaveBean(CircuitBreaker.class);
            assertThat(context).doesNotHaveBean(SesSmtpProperties.class);
            assertThat(context.getBean(ProviderRegistry.class).hasDeliveryProvider()).isFalse();
            assertThat(context.getBean(OtpDeliveryCapability.class).mode()).isEqualTo(OtpDeliveryMode.UNAVAILABLE);
        });
    }

    @Test
    void explicitlyDisabledItIgnoresInvalidValues() {
        runner.withPropertyValues(
                        "notifications.email.ses.enabled=false",
                        "notifications.email.ses.host=",
                        "notifications.email.ses.password=",
                        "notifications.email.ses.port=not-a-number")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(SesEmailProvider.class));
    }

    @Test
    void disabledTheDiagnosticConsoleKeepsWorkingWithItsOptIn() {
        runner.withPropertyValues("notifications.console.show-sensitive-content=true")
                .run(context -> assertThat(context.getBean(OtpDeliveryCapability.class).mode())
                        .isEqualTo(OtpDeliveryMode.DIAGNOSTIC_CONSOLE));
    }

    // ── Habilitado ──────────────────────────────────────────────────────

    @Test
    void enabledAndCompleteItIsTheOnlyEmailProviderAndQualifiesForCodes() {
        runner.withPropertyValues(COMPLETE).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(SesEmailProvider.class);

            ProviderRegistry registry = context.getBean(ProviderRegistry.class);

            assertThat(registry.getProvider(NotificationChannel.EMAIL)).isInstanceOf(SesEmailProvider.class);
            assertThat(registry.getProvider(NotificationChannel.EMAIL).providerName()).isEqualTo("ses");
            assertThat(context.getBeansOfType(NotificationProvider.class).values())
                    .hasExactlyElementsOfTypes(ConsoleNotificationProvider.class, SesEmailProvider.class);
            assertThat(context.getBean(OtpDeliveryCapability.class).mode()).isEqualTo(OtpDeliveryMode.EMAIL);
        });
    }

    @Test
    void withARealProviderTheConsoleOptInDoesNotChangeTheOtpMode() {
        runner.withPropertyValues(COMPLETE)
                .withPropertyValues("notifications.console.show-sensitive-content=true")
                .run(context -> assertThat(context.getBean(OtpDeliveryCapability.class).mode())
                        .isEqualTo(OtpDeliveryMode.EMAIL));
    }

    @Test
    void startingContactsNoServer() throws Exception {
        try (ServerSocket listener = new ServerSocket(0)) {
            listener.setSoTimeout(300);

            runner.withPropertyValues(COMPLETE)
                    .withPropertyValues(
                            "notifications.email.ses.host=localhost",
                            "notifications.email.ses.port=" + listener.getLocalPort())
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        org.assertj.core.api.Assertions
                                .assertThatThrownBy(listener::accept)
                                .isInstanceOf(java.net.SocketTimeoutException.class);
                    });
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"host", "username", "password", "from-email"})
    void enabledWithoutARequiredPropertyFailsNamingItAndNotTheSecret(String property) {
        String[] incomplete = Stream.of(COMPLETE)
                .filter(entry -> !entry.startsWith("notifications.email.ses." + property + "="))
                .toArray(String[]::new);

        runner.withPropertyValues(incomplete).run(context -> {
            assertThat(context).hasFailed();
            assertThat(allMessages(context.getStartupFailure()))
                    .contains("notifications.email.ses." + property)
                    .doesNotContain(SYNTHETIC_PASSWORD);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "notifications.email.ses.password=   ",
            "notifications.email.ses.host=smtp.example;evil",
            "notifications.email.ses.port=0",
            "notifications.email.ses.from-email=remitente",
            "notifications.email.ses.from-email=señal@piedrazul.example",
            "notifications.email.ses.connect-timeout=0s",
            "notifications.email.ses.read-timeout=-1s",
            "notifications.email.ses.write-timeout=0s",
            "notifications.email.ses.total-deadline=0s",
    })
    void enabledWithAnInvalidValueFails(String invalid) {
        runner.withPropertyValues(COMPLETE)
                .withPropertyValues(invalid)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(allMessages(context.getStartupFailure())).doesNotContain(SYNTHETIC_PASSWORD);
                });
    }

    @Test
    void thePropertiesNeverPrintTheirCredentials() {
        runner.withPropertyValues(COMPLETE).run(context ->
                assertThat(context.getBean(SesSmtpProperties.class).toString())
                        .doesNotContain(SYNTHETIC_PASSWORD)
                        .doesNotContain("SYNTHETICUSER"));
    }

    @Test
    void timeoutsDefaultToTheInitialCandidatesAndCanBeChanged() {
        runner.withPropertyValues(COMPLETE).run(context -> {
            SesSmtpProperties properties = context.getBean(SesSmtpProperties.class);

            assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
            assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(15));
            assertThat(properties.writeTimeout()).isEqualTo(Duration.ofSeconds(15));
            assertThat(properties.totalDeadline()).isEqualTo(Duration.ofSeconds(60));
        });

        runner.withPropertyValues(COMPLETE)
                .withPropertyValues("notifications.email.ses.total-deadline=90s")
                .run(context -> assertThat(context.getBean(SesSmtpProperties.class).totalDeadline())
                        .isEqualTo(Duration.ofSeconds(90)));
    }

    // ── Circuit breaker ─────────────────────────────────────────────────

    @Test
    void theBreakerExistsOnlyWhenEnabledAndHonorsItsDefaults() {
        runner.withPropertyValues(COMPLETE).run(context -> {
            CircuitBreaker breaker = context.getBean(CircuitBreaker.class);
            CircuitBreakerConfig config = breaker.getCircuitBreakerConfig();

            assertThat(breaker.getName()).isEqualTo("ses");
            assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
            assertThat(config.getSlidingWindowType()).isEqualTo(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED);
            assertThat(config.getSlidingWindowSize()).isEqualTo(10);
            assertThat(config.getMinimumNumberOfCalls()).isEqualTo(5);
            assertThat(config.getFailureRateThreshold()).isEqualTo(50f);
            assertThat(config.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(3);
            assertThat(config.getWaitIntervalFunctionInOpenState().apply(1)).isEqualTo(30_000L);
        });
    }

    @Test
    void theBreakerHonorsConfiguredSettingsInTheContext() {
        runner.withPropertyValues(COMPLETE)
                .withPropertyValues(
                        "notifications.email.ses.circuit-breaker.minimum-calls=2",
                        "notifications.email.ses.circuit-breaker.window-size=4",
                        "notifications.email.ses.circuit-breaker.failure-rate=75",
                        "notifications.email.ses.circuit-breaker.open-wait=45s",
                        "notifications.email.ses.circuit-breaker.half-open-calls=1")
                .run(context -> {
                    CircuitBreakerConfig config = context.getBean(CircuitBreaker.class).getCircuitBreakerConfig();

                    assertThat(config.getMinimumNumberOfCalls()).isEqualTo(2);
                    assertThat(config.getSlidingWindowSize()).isEqualTo(4);
                    assertThat(config.getFailureRateThreshold()).isEqualTo(75f);
                    assertThat(config.getWaitIntervalFunctionInOpenState().apply(1)).isEqualTo(45_000L);
                    assertThat(config.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(1);
                });
    }

    @Test
    void theContextClosesTheWatchdogThread() {
        runner.withPropertyValues(COMPLETE).run(context -> assertThat(context).hasSingleBean(SesSmtpSender.class));

        assertThat(Thread.getAllStackTraces().keySet()).noneMatch(thread ->
                thread.getName().startsWith("ses-smtp-deadline") && thread.isAlive());
    }

    // ── Sin caminos alternativos ────────────────────────────────────────

    @Test
    void theRetiredSendGridSwitchHasNoEffect() {
        runner.withPropertyValues(
                        "notifications.email.sendgrid.enabled=true",
                        "notifications.email.sendgrid.api-key=synthetic-key")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ProviderRegistry.class).hasProvider(NotificationChannel.EMAIL)).isFalse();
                });
    }

    @Test
    void sendGridAndTheMailHealthContributorAreNotOnTheClasspath() {
        ClassLoader loader = getClass().getClassLoader();

        assertThat(ClassUtils.isPresent("com.sendgrid.SendGrid", loader)).isFalse();
        assertThat(ClassUtils.isPresent(
                "co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.email.sendgrid.SendGridEmailProvider",
                loader)).isFalse();
        // Spring Boot solo aporta el indicador de salud de correo sobre JavaMailSender.
        assertThat(ClassUtils.isPresent("org.springframework.mail.javamail.JavaMailSender", loader)).isFalse();
        assertThat(ClassUtils.isPresent(
                "io.github.resilience4j.springboot3.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration",
                loader)).isFalse();
    }

    private static String allMessages(Throwable failure) {
        StringBuilder messages = new StringBuilder();

        for (Throwable current = failure; current != null; current = current.getCause()) {
            messages.append(current).append('\n');
        }

        return messages.toString();
    }
}
