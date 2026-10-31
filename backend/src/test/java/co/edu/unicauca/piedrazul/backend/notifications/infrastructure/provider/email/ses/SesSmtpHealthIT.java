package co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.email.ses;

import co.edu.unicauca.piedrazul.backend.notifications.api.OtpDeliveryMode;
import co.edu.unicauca.piedrazul.backend.notifications.application.OtpDeliveryCapability;
import co.edu.unicauca.piedrazul.backend.notifications.application.ProviderRegistry;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationChannel;
import co.edu.unicauca.piedrazul.backend.support.PostgresIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Aplicación completa con SES habilitado contra un servidor que nunca responde (dominio reservado
 * {@code .invalid}): arranca, usa SES como canal de códigos y su salud no depende del correo.
 */
@SpringBootTest(properties = {
        "notifications.email.ses.enabled=true",
        "notifications.email.ses.host=smtp.unreachable.invalid",
        "notifications.email.ses.username=SYNTHETICUSER",
        "notifications.email.ses.password=synthetic-smtp-password",
        "notifications.email.ses.from-email=no-reply@piedrazul.example",
        "management.endpoint.health.show-components=always"
})
@AutoConfigureMockMvc
@Import(SesSmtpHealthIT.TestSecurityBeans.class)
class SesSmtpHealthIT extends PostgresIntegrationSupport {

    @TestConfiguration
    static class TestSecurityBeans {
        /** Evita resolver el issuer real de Keycloak al arrancar; ninguna petición lo usa. */
        @Bean
        JwtDecoder jwtDecoder() {
            return mock(JwtDecoder.class);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProviderRegistry providerRegistry;

    @Autowired
    private OtpDeliveryCapability otpDeliveryCapability;

    @Test
    void healthStaysUpWithAnUnreachableMailServerAndHasNoMailComponent() throws Exception {
        assertThat(providerRegistry.getProvider(NotificationChannel.EMAIL)).isInstanceOf(SesEmailProvider.class);
        assertThat(otpDeliveryCapability.mode()).isEqualTo(OtpDeliveryMode.EMAIL);

        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.mail").doesNotExist())
                .andExpect(jsonPath("$.components.circuitBreakers").doesNotExist());
    }
}
