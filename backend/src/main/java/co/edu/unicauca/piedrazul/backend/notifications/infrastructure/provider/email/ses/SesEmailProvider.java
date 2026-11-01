package co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.email.ses;

import co.edu.unicauca.piedrazul.backend.notifications.application.RecipientEmailPolicy;
import co.edu.unicauca.piedrazul.backend.notifications.application.exception.NotificationDispatchException;
import co.edu.unicauca.piedrazul.backend.notifications.domain.message.ChannelMessage;
import co.edu.unicauca.piedrazul.backend.notifications.domain.message.EmailMessage;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.AttemptStatus;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.FailureType;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationChannel;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.RecipientSnapshot;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.DeliveryOutcome;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.NotificationProvider;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.NotificationSendResult;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.email.ses.SmtpOutcome.Health;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Proveedor de correo por el SMTP de Amazon SES. Declara el resultado de cada envío; no reintenta
 * ni cambia de canal: eso lo decide el despachador. Nunca devuelve texto del servidor ni de
 * excepciones, y no escribe en el log.
 *
 * <p>Lo local (tipo de mensaje, destinatario, MIME) se resuelve antes de pedir permiso al circuit
 * breaker, de modo que un mensaje defectuoso no consume llamadas ni afecta a la salud del proveedor.
 * Con el circuito abierto no se abre ninguna conexión.
 */
public class SesEmailProvider implements NotificationProvider {

    static final String PROVIDER_NAME = "ses";

    private static final String CHARSET = StandardCharsets.UTF_8.name();

    // Sin traza ni mensaje: solo marca el fallo ante el circuit breaker.
    private static final Exception PROVIDER_FAULT = new ProviderFault();

    private final SesSmtpSender sender;
    private final Session mimeSession;
    private final SesSmtpProperties properties;
    private final CircuitBreaker circuitBreaker;
    private final RecipientEmailPolicy emailPolicy;

    SesEmailProvider(
            SesSmtpSender sender,
            SesSmtpProperties properties,
            CircuitBreaker circuitBreaker,
            RecipientEmailPolicy emailPolicy
    ) {
        this.sender = sender;
        this.properties = properties;
        this.mimeSession = mimeSession(properties.fromEmail());
        this.circuitBreaker = circuitBreaker;
        this.emailPolicy = emailPolicy;
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.EMAIL;
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @Override
    public boolean qualifiesForOtpDelivery() {
        return true;
    }

    @Override
    public NotificationSendResult send(ChannelMessage message, RecipientSnapshot recipient) {
        if (!(message instanceof EmailMessage email)) {
            return localRejection("SES_MESSAGE_UNSUPPORTED");
        }

        Optional<String> address = recipient == null
                ? Optional.empty()
                : emailPolicy.deliverableAddress(recipient.email());

        if (address.isEmpty()) {
            return localRejection("SES_RECIPIENT_UNDELIVERABLE");
        }

        Optional<MimeMessage> mime = buildMessage(email, address.get());

        if (mime.isEmpty()) {
            return localRejection("SES_MESSAGE_INVALID");
        }

        if (!circuitBreaker.tryAcquirePermission()) {
            throw new NotificationDispatchException(
                    NotificationDispatchException.CIRCUIT_OPEN,
                    "Circuit breaker de SES abierto: no se abrió ninguna conexión"
            );
        }

        long startedAt = circuitBreaker.getCurrentTimestamp();
        boolean reported = false;

        try {
            SmtpOutcome outcome = sender.send(mime.get());
            report(outcome.health(), circuitBreaker.getCurrentTimestamp() - startedAt);
            reported = true;
            return toResult(outcome);
        } finally {
            if (!reported) {
                circuitBreaker.releasePermission();
            }
        }
    }

    private void report(Health health, long elapsed) {
        TimeUnit unit = circuitBreaker.getTimestampUnit();

        switch (health) {
            case HEALTHY -> circuitBreaker.onSuccess(elapsed, unit);
            case FAULT -> circuitBreaker.onError(elapsed, unit, PROVIDER_FAULT);
            case NEUTRAL -> circuitBreaker.releasePermission();
        }
    }

    /**
     * Construye el mensaje y lo serializa una vez sin red: un fallo local aparece aquí, antes de
     * abrir la conexión, y no a mitad del cuerpo, donde ya no se distinguiría de una pérdida.
     */
    private Optional<MimeMessage> buildMessage(EmailMessage email, String address) {
        if (hasLineBreak(email.subject())) {
            return Optional.empty();
        }

        try {
            MimeMessage mime = new MimeMessage(mimeSession);

            mime.setFrom(new InternetAddress(properties.fromEmail(), properties.fromName(), CHARSET));
            mime.setRecipient(Message.RecipientType.TO, new InternetAddress(address));
            mime.setSubject(email.subject(), CHARSET);
            mime.setSentDate(new Date());
            setContent(mime, email);
            mime.saveChanges();
            mime.writeTo(OutputStream.nullOutputStream());

            return Optional.of(mime);
        } catch (MessagingException | IOException | RuntimeException exception) {
            return Optional.empty();
        }
    }

    /** Solo construye mensajes; con {@code mail.from} el Message-ID no consulta el nombre del host. */
    private static Session mimeSession(String fromEmail) {
        Properties props = new Properties();
        props.setProperty("mail.from", fromEmail);
        return Session.getInstance(props);
    }

    private static void setContent(MimeMessage mime, EmailMessage email) throws MessagingException {
        boolean hasText = email.textBody() != null && !email.textBody().isBlank();
        boolean hasHtml = email.htmlBody() != null && !email.htmlBody().isBlank();

        if (hasText && hasHtml) {
            MimeBodyPart text = new MimeBodyPart();
            text.setText(email.textBody(), CHARSET, "plain");

            MimeBodyPart html = new MimeBodyPart();
            html.setText(email.htmlBody(), CHARSET, "html");

            mime.setContent(new MimeMultipart("alternative", text, html));
        } else if (hasHtml) {
            mime.setText(email.htmlBody(), CHARSET, "html");
        } else if (hasText) {
            mime.setText(email.textBody(), CHARSET, "plain");
        } else {
            throw new MessagingException("Empty message");
        }
    }

    private static boolean hasLineBreak(String value) {
        return value == null || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0;
    }

    private static NotificationSendResult localRejection(String code) {
        return toResult(SmtpOutcome.of(DeliveryOutcome.REJECTED, Health.NEUTRAL, code));
    }

    private static NotificationSendResult toResult(SmtpOutcome outcome) {
        return new NotificationSendResult(
                PROVIDER_NAME,
                NotificationChannel.EMAIL,
                outcome.providerMessageId(),
                outcome.outcome() == DeliveryOutcome.ACCEPTED ? AttemptStatus.ACCEPTED
                        : outcome.outcome() == DeliveryOutcome.UNKNOWN ? AttemptStatus.UNKNOWN
                        : AttemptStatus.FAILED,
                switch (outcome.outcome()) {
                    case ACCEPTED -> null;
                    case RETRYABLE -> FailureType.TEMPORARY;
                    case REJECTED, PROVIDER_ERROR -> FailureType.PERMANENT;
                    case UNKNOWN -> FailureType.UNKNOWN;
                },
                outcome.code(),
                outcome.code() == null ? null : "Resultado SMTP: " + outcome.code(),
                outcome.outcome()
        );
    }

    private static final class ProviderFault extends Exception {
        private ProviderFault() {
            super(null, null, false, false);
        }
    }
}
