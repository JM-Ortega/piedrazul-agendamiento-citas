package co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.email.ses;

import co.edu.unicauca.piedrazul.backend.notifications.application.RecipientEmailPolicy;
import co.edu.unicauca.piedrazul.backend.notifications.domain.message.EmailMessage;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.RecipientSnapshot;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import jakarta.mail.Session;
import jakarta.mail.URLName;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;

/** Piezas comunes de las pruebas del proveedor SES. Credenciales y datos exclusivamente sintéticos. */
public final class SesSmtpTestKit {

    static final String USERNAME = "SYNTHETICUSER";
    static final String PASSWORD = "synthetic-smtp-password";
    static final String FROM = "no-reply@piedrazul.example";
    static final String TO = "paciente@correo.example";
    static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();
    private static final Duration WATCHDOG_BOUND = Duration.ofSeconds(5);

    private SesSmtpTestKit() {
    }

    static SesSmtpProperties properties(int port, Duration read, Duration write, Duration deadline) {
        return new SesSmtpProperties(
                "localhost", port, USERNAME, PASSWORD, FROM, "Piedrazul Salud",
                Duration.ofSeconds(2), read, write, deadline, breakerSettings());
    }

    static SesSmtpProperties.CircuitBreakerSettings breakerSettings() {
        return new SesSmtpProperties.CircuitBreakerSettings(5, 10, 50f, Duration.ofSeconds(30), 3);
    }

    static CircuitBreaker breaker() {
        return breaker(breakerSettings());
    }

    static CircuitBreaker breaker(SesSmtpProperties.CircuitBreakerSettings settings) {
        return CircuitBreaker.of("ses-test", CircuitBreakerConfig.custom()
                .slidingWindowSize(settings.windowSize())
                .minimumNumberOfCalls(settings.minimumCalls())
                .failureRateThreshold(settings.failureRate())
                .waitDurationInOpenState(settings.openWait())
                .permittedNumberOfCallsInHalfOpenState(settings.halfOpenCalls())
                .build());
    }

    static Harness harness(LocalSmtpServer server, Duration read, Duration deadline) {
        return harness(server, read, deadline, breaker(), SmtpTransaction::new);
    }

    static Harness harness(
            LocalSmtpServer server,
            Duration read,
            Duration deadline,
            CircuitBreaker breaker,
            BiFunction<Session, URLName, SmtpTransaction> transactions
    ) {
        SesSmtpProperties properties = properties(server.port(), read, Duration.ofSeconds(5), deadline);
        SesSmtpSender sender = new SesSmtpSender(properties, LocalSmtpServer.Tls.forHost("localhost").client(), transactions);

        return new Harness(sender, new SesEmailProvider(sender, properties, breaker, new RecipientEmailPolicy(VALIDATOR)));
    }

    static ScheduledThreadPoolExecutor watchdog(SesSmtpSender sender) {
        return (ScheduledThreadPoolExecutor) ReflectionTestUtils.getField(sender, "watchdog");
    }

    /**
     * Hilo vigilante de este emisor, identificado por sí mismo y no por su nombre, que comparte con
     * los de cualquier otro emisor. El ejecutor lo crea al primer uso, así que esto también lo arranca.
     */
    static Thread watchdogThread(SesSmtpSender sender) throws Exception {
        Callable<Thread> current = Thread::currentThread;

        return watchdog(sender).submit(current).get(WATCHDOG_BOUND.toSeconds(), TimeUnit.SECONDS);
    }

    /** El hilo termina después del cierre, no durante: se espera a que acabe, con un límite. */
    static void assertWatchdogStopped(SesSmtpSender sender, Thread thread) throws InterruptedException {
        thread.join(WATCHDOG_BOUND);

        assertThat(thread.isAlive()).as("hilo vigilante vivo tras el cierre").isFalse();
        assertThat(watchdog(sender).isTerminated()).as("vigilante terminado").isTrue();
    }

    static EmailMessage email() {
        return new EmailMessage("Código de verificación", "<p>Código 000111</p>", "Código 000111");
    }

    static RecipientSnapshot recipient(String email) {
        return new RecipientSnapshot(
                UUID.randomUUID(), "Paciente Sintético", null, email, Locale.forLanguageTag("es-CO"), null);
    }

    record Harness(SesSmtpSender sender, SesEmailProvider provider) implements AutoCloseable {
        @Override
        public void close() {
            sender.destroy();
        }
    }

    /** Proveedor SES apuntando a un servidor local, para probarlo con otras capas. */
    public static final class LocalProvider implements AutoCloseable {
        private final LocalSmtpServer server;
        private final Harness harness;

        private LocalProvider(LocalSmtpServer server, Harness harness) {
            this.server = server;
            this.harness = harness;
        }

        public SesEmailProvider provider() {
            return harness.provider();
        }

        public int connections() {
            return server.connections();
        }

        public int acceptedMessages() {
            return server.messages().size();
        }

        @Override
        public void close() throws Exception {
            harness.close();
            server.close();
        }
    }

    /** El servidor acepta todo. */
    public static LocalProvider accepting() throws Exception {
        LocalSmtpServer server = new LocalSmtpServer();
        return new LocalProvider(server, harness(server, Duration.ofSeconds(5), Duration.ofSeconds(10)));
    }

    /** La conexión se pierde tras enviar el mensaje, sin respuesta final. */
    public static LocalProvider losingFinalReply() throws Exception {
        LocalSmtpServer server = new LocalSmtpServer().on(LocalSmtpServer.Step.FINAL, LocalSmtpServer.closeNow());
        return new LocalProvider(server, harness(server, Duration.ofSeconds(5), Duration.ofSeconds(10)));
    }

    /** El servidor rechaza temporalmente el mensaje en la respuesta final. */
    public static LocalProvider throttling() throws Exception {
        LocalSmtpServer server = new LocalSmtpServer()
                .on(LocalSmtpServer.Step.FINAL, LocalSmtpServer.reply("454 Throttling failure"));
        return new LocalProvider(server, harness(server, Duration.ofSeconds(5), Duration.ofSeconds(10)));
    }

    /** El servidor rechaza al destinatario. */
    public static LocalProvider rejectingRecipient() throws Exception {
        LocalSmtpServer server = new LocalSmtpServer()
                .on(LocalSmtpServer.Step.RCPT, LocalSmtpServer.reply("550 No such user"));
        return new LocalProvider(server, harness(server, Duration.ofSeconds(5), Duration.ofSeconds(10)));
    }
}
