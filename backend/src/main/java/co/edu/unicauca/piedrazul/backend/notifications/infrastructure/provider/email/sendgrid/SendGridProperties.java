package co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.email.sendgrid;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "notifications.email.sendgrid")
public record SendGridProperties(
        boolean enabled,
        String apiKey,
        String fromEmail,
        String fromName,
        boolean sandboxMode
) {

    public SendGridProperties {
        if (enabled && (apiKey == null || apiKey.isBlank())) {
            throw new IllegalStateException(
                    "SENDGRID_API_KEY es obligatorio cuando notifications.email.sendgrid.enabled=true"
            );
        }
    }
}