package co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.email.ses;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Configuración del correo por el SMTP de Amazon SES. Solo se enlaza con el proveedor habilitado:
 * deshabilitado, el backend arranca sin ninguna de estas propiedades. Habilitado, una configuración
 * incompleta impide el arranque; los mensajes nombran propiedades, nunca sus valores.
 */
@ConfigurationProperties(prefix = "notifications.email.ses")
public record SesSmtpProperties(
        String host,
        @DefaultValue("587") int port,
        String username,
        String password,
        String fromEmail,
        @DefaultValue("Piedrazul") String fromName,
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("15s") Duration readTimeout,
        @DefaultValue("15s") Duration writeTimeout,
        @DefaultValue("60s") Duration totalDeadline,
        @DefaultValue CircuitBreakerSettings circuitBreaker
) {

    private static final String PREFIX = "notifications.email.ses.";
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9.-]+");

    public SesSmtpProperties {
        List<String> problems = new ArrayList<>();

        if (host == null || !HOST.matcher(host).matches()) {
            problems.add(PREFIX + "host");
        }

        if (port < 1 || port > 65535) {
            problems.add(PREFIX + "port");
        }

        if (isBlank(username)) {
            problems.add(PREFIX + "username");
        }

        if (isBlank(password)) {
            problems.add(PREFIX + "password");
        }

        if (!isDeliverableSender(fromEmail)) {
            problems.add(PREFIX + "from-email");
        }

        if (isBlank(fromName)) {
            problems.add(PREFIX + "from-name");
        }

        requirePositiveMillis(connectTimeout, "connect-timeout", problems);
        requirePositiveMillis(readTimeout, "read-timeout", problems);
        requirePositiveMillis(writeTimeout, "write-timeout", problems);
        requirePositiveMillis(totalDeadline, "total-deadline", problems);

        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "Correo SES habilitado con configuración incompleta o inválida: "
                            + String.join(", ", problems));
        }
    }

    /** Sin credenciales: esta representación puede acabar en un log. */
    @Override
    public String toString() {
        return "SesSmtpProperties[host=" + host + ", port=" + port + "]";
    }

    int connectTimeoutMillis() {
        return (int) connectTimeout.toMillis();
    }

    int readTimeoutMillis() {
        return (int) readTimeout.toMillis();
    }

    int writeTimeoutMillis() {
        return (int) writeTimeout.toMillis();
    }

    /** SES no admite SMTPUTF8: el remitente debe ser una dirección ASCII literal. */
    private static boolean isDeliverableSender(String email) {
        if (isBlank(email) || !email.chars().allMatch(c -> c > 0x20 && c < 0x7F)) {
            return false;
        }

        try {
            new InternetAddress(email, true);
            return true;
        } catch (AddressException exception) {
            return false;
        }
    }

    private static void requirePositiveMillis(Duration value, String name, List<String> problems) {
        if (value == null
                || value.isNegative()
                || value.toMillis() < 1
                || value.toMillis() > Integer.MAX_VALUE) {
            problems.add(PREFIX + name);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** Ajustes iniciales del circuit breaker; no hay regla de llamadas lentas sin calibrarla. */
    public record CircuitBreakerSettings(
            @DefaultValue("5") int minimumCalls,
            @DefaultValue("10") int windowSize,
            @DefaultValue("50") float failureRate,
            @DefaultValue("30s") Duration openWait,
            @DefaultValue("3") int halfOpenCalls
    ) {
    }
}
