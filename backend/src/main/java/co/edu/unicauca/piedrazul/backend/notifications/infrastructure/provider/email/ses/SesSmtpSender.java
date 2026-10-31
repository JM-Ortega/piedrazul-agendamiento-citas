package co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.email.ses;

import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.DeliveryOutcome;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.email.ses.ReplyFramingSslSocketFactory.ReplyFraming;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.email.ses.SmtpOutcome.Health;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.URLName;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.DisposableBean;

import javax.net.ssl.SSLSocketFactory;
import java.util.Properties;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

/**
 * Hace un envío SMTP con una conexión propia, bajo un plazo total que no depende del hilo que lo
 * ejecuta. Lo vigila un único hilo daemon propio: el plazo no puede programarse en el planificador
 * de Spring, cuyo único hilo es el que queda bloqueado en el socket. Ese hilo solo cierra el socket
 * real ({@link SmtpDeadlineGuard}); el resultado lo publica siempre el hilo del envío, de modo que
 * no hay carrera entre el vencimiento y la respuesta final: lo que decide es qué llegó a leerse.
 * Una aceptación ya leída se conserva aunque después venzan el plazo, {@code QUIT} o el cierre.
 *
 * <p>El plazo acota la E/S de sockets, incluido el TLS y la conexión. No interrumpe una resolución
 * de DNS en curso: si vence durante ella, la conexión no llega a abrirse al terminar.
 */
class SesSmtpSender implements DisposableBean {

    private static final String PROTOCOL = "smtp";
    private static final String PREFIX = "mail." + PROTOCOL + ".";

    private final SesSmtpProperties properties;
    private final SSLSocketFactory tlsSocketFactory;
    private final BiFunction<Session, URLName, SmtpTransaction> transactions;
    private final ScheduledThreadPoolExecutor watchdog;

    /** @param tlsSocketFactory confianza TLS propia; {@code null} usa la de la JVM */
    SesSmtpSender(SesSmtpProperties properties, SSLSocketFactory tlsSocketFactory) {
        this(properties, tlsSocketFactory, SmtpTransaction::new);
    }

    SesSmtpSender(
            SesSmtpProperties properties,
            SSLSocketFactory tlsSocketFactory,
            BiFunction<Session, URLName, SmtpTransaction> transactions
    ) {
        this.properties = properties;
        this.tlsSocketFactory = tlsSocketFactory != null
                ? tlsSocketFactory
                : (SSLSocketFactory) SSLSocketFactory.getDefault();
        this.transactions = transactions;
        this.watchdog = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "ses-smtp-deadline");
            thread.setDaemon(true);
            return thread;
        });
        this.watchdog.setRemoveOnCancelPolicy(true);
    }

    SmtpOutcome send(MimeMessage message) {
        SmtpDeadlineGuard guard = new SmtpDeadlineGuard();
        ScheduledFuture<?> deadline;

        try {
            deadline = watchdog.schedule(
                    guard::expire, properties.totalDeadline().toMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException exception) {
            // La aplicación se detiene: sin vigilante no se abre ninguna conexión.
            return SmtpOutcome.of(DeliveryOutcome.RETRYABLE, Health.NEUTRAL, "SES_SMTP_SHUTTING_DOWN");
        }

        // Todo lo que siga, también lo que falle al prepararlo, libera el plazo y el socket.
        try {
            SmtpTransaction transaction;

            try {
                transaction = prepare(guard);
            } catch (RuntimeException exception) {
                // Nada salió a la red: la configuración local del transporte no es utilizable.
                return SmtpOutcome.of(DeliveryOutcome.PROVIDER_ERROR, Health.FAULT, "SES_SMTP_SETUP_FAILED");
            }

            try {
                return transmit(transaction, message, guard);
            } finally {
                closeQuietly(transaction);
            }
        } finally {
            deadline.cancel(false);
            guard.abort();
        }
    }

    private SmtpTransaction prepare(SmtpDeadlineGuard guard) {
        ReplyFraming framing = new ReplyFraming();
        Session session = Session.getInstance(sessionProperties(guard, framing));
        URLName url = new URLName(PROTOCOL, properties.host(), properties.port(), null, null, null);
        SmtpTransaction transaction = transactions.apply(session, url);

        transaction.observeFraming(framing);
        return transaction;
    }

    private SmtpOutcome transmit(
            SmtpTransaction transaction,
            MimeMessage message,
            SmtpDeadlineGuard guard
    ) {
        try {
            transaction.connect(properties.host(), properties.port(), properties.username(), properties.password());

            if (!transaction.supportsExtension("AUTH")) {
                throw new MessagingException("SMTP server did not offer authentication");
            }

            transaction.sendMessage(message, message.getAllRecipients());
        } catch (MessagingException | RuntimeException exception) {
            // Una aceptación ya leída no depende de lo que falle después.
            if (!transaction.accepted()) {
                return SmtpFailureClassifier.classify(transaction, exception, guard.expired());
            }
        }

        return transaction.accepted()
                ? SmtpOutcome.accepted(transaction.providerMessageId())
                : SmtpOutcome.of(DeliveryOutcome.UNKNOWN, Health.FAULT, SmtpFailureClassifier.UNCONFIRMED);
    }

    private static void closeQuietly(SmtpTransaction transaction) {
        try {
            transaction.close();
        } catch (MessagingException | RuntimeException exception) {
            // El resultado ya está decidido; el socket lo libera el guardián.
        }
    }

    private Properties sessionProperties(SmtpDeadlineGuard guard, ReplyFraming framing) {
        Properties props = new Properties();

        props.put(PREFIX + "host", properties.host());
        props.put(PREFIX + "port", String.valueOf(properties.port()));
        props.put(PREFIX + "from", properties.fromEmail());
        props.put("mail.from", properties.fromEmail());
        props.put(PREFIX + "auth", "true");
        props.put(PREFIX + "auth.mechanisms", "PLAIN LOGIN");

        props.put(PREFIX + "starttls.enable", "true");
        props.put(PREFIX + "starttls.required", "true");
        props.put(PREFIX + "ssl.checkserveridentity", "true");
        props.put(PREFIX + "ssl.protocols", "TLSv1.3 TLSv1.2");

        props.put(PREFIX + "connectiontimeout", String.valueOf(properties.connectTimeoutMillis()));
        props.put(PREFIX + "timeout", String.valueOf(properties.readTimeoutMillis()));
        props.put(PREFIX + "writetimeout", String.valueOf(properties.writeTimeoutMillis()));
        // Sin este ejecutor Angus crea un hilo por conexión. Se apaga con el emisor, no con ella.
        props.put(PREFIX + "executor.writetimeout", watchdog);

        props.put(PREFIX + "socketFactory", guard);
        props.put(PREFIX + "socketFactory.fallback", "false");

        props.put(PREFIX + "ssl.socketFactory", new ReplyFramingSslSocketFactory(tlsSocketFactory, framing));

        // El cierre tras la aceptación no espera respuesta.
        props.put(PREFIX + "quitwait", "false");
        props.put(PREFIX + "sendpartial", "false");
        props.put("mail.debug", "false");
        props.put("mail.mime.allowutf8", "false");

        return props;
    }

    /**
     * Ningún envío nuevo se admite; los plazos pendientes siguen corriendo hasta vencer o
     * cancelarse, y el hilo termina con ellos. Así un envío bloqueado no queda sin vigilante
     * durante el apagado.
     */
    @Override
    public void destroy() {
        watchdog.shutdown();
    }
}
