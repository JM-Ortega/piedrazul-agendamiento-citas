package co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.email.ses;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El contrato del despliegue con el backend: los nombres que Compose entrega en el entorno pasan por
 * el {@code application.yaml} real hasta {@link SesSmtpProperties}. Deshabilitado llegan vacíos, tal
 * como los deja {@code env.j2}. Compose no entrega puerto, plazos ni circuit breaker: rigen los
 * valores por defecto de la aplicación.
 */
class SesEnvironmentBindingTest {

    private static final String SYNTHETIC_USERNAME = "SYNTHETICUSER";
    private static final String SYNTHETIC_PASSWORD = "synthetic+smtp/password=";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(SesSmtpConfig.class, SesSmtpConfigTest.Collaborators.class);

    @Test
    void enabledTheComposeVariablesReachTheProperties() {
        runner.withPropertyValues(
                        "NOTIFICATIONS_SES_ENABLED=true",
                        "SES_SMTP_HOST=email-smtp.us-east-1.amazonaws.com",
                        "SES_SMTP_USERNAME=" + SYNTHETIC_USERNAME,
                        "SES_SMTP_PASSWORD=" + SYNTHETIC_PASSWORD,
                        "NOTIFICATIONS_EMAIL_FROM=no-reply@mail.piedrazul.org",
                        "NOTIFICATIONS_EMAIL_FROM_NAME=Piedrazul Salud")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(SesEmailProvider.class);

                    SesSmtpProperties properties = context.getBean(SesSmtpProperties.class);

                    assertThat(properties.host()).isEqualTo("email-smtp.us-east-1.amazonaws.com");
                    assertThat(properties.username()).isEqualTo(SYNTHETIC_USERNAME);
                    assertThat(properties.password()).isEqualTo(SYNTHETIC_PASSWORD);
                    assertThat(properties.fromEmail()).isEqualTo("no-reply@mail.piedrazul.org");
                    assertThat(properties.fromName()).isEqualTo("Piedrazul Salud");
                    assertThat(properties.port()).isEqualTo(587);
                    assertThat(properties.totalDeadline()).isEqualTo(Duration.ofSeconds(60));
                });
    }

    @Test
    void disabledTheEmptyComposeVariablesRegisterNoProvider() {
        runner.withPropertyValues(
                        "NOTIFICATIONS_SES_ENABLED=false",
                        "SES_SMTP_HOST=",
                        "SES_SMTP_USERNAME=",
                        "SES_SMTP_PASSWORD=",
                        "NOTIFICATIONS_EMAIL_FROM=no-reply@mail.piedrazul.org",
                        "NOTIFICATIONS_EMAIL_FROM_NAME=Piedrazul Salud")
                .run(context -> assertThat(context).hasNotFailed()
                        .doesNotHaveBean(SesEmailProvider.class)
                        .doesNotHaveBean(SesSmtpProperties.class));
    }

    @Test
    void withoutAnyComposeVariableItStaysDisabled() {
        runner.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(SesEmailProvider.class));
    }

    @Test
    void enabledWithAnEmptyCredentialTheContextDoesNotStartNorRevealsTheOthers() {
        runner.withPropertyValues(
                        "NOTIFICATIONS_SES_ENABLED=true",
                        "SES_SMTP_HOST=email-smtp.us-east-1.amazonaws.com",
                        "SES_SMTP_USERNAME=",
                        "SES_SMTP_PASSWORD=" + SYNTHETIC_PASSWORD,
                        "NOTIFICATIONS_EMAIL_FROM=no-reply@mail.piedrazul.org")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .hasMessageContaining("notifications.email.ses.username")
                            .hasMessageNotContaining(SYNTHETIC_PASSWORD);
                });
    }
}
