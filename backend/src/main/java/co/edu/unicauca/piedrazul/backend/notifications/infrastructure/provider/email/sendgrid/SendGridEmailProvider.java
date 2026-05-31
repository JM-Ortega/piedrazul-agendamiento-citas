package co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.email.sendgrid;

import co.edu.unicauca.piedrazul.backend.notifications.application.exception.NotificationDispatchException;
import co.edu.unicauca.piedrazul.backend.notifications.domain.message.ChannelMessage;
import co.edu.unicauca.piedrazul.backend.notifications.domain.message.EmailMessage;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.AttemptStatus;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationChannel;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.RecipientSnapshot;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.NotificationProvider;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.NotificationSendResult;
import com.sendgrid.Method;
import com.sendgrid.Request;
import com.sendgrid.Response;
import com.sendgrid.SendGrid;
import com.sendgrid.helpers.mail.Mail;
import com.sendgrid.helpers.mail.objects.Content;
import com.sendgrid.helpers.mail.objects.Email;
import com.sendgrid.helpers.mail.objects.MailSettings;
import com.sendgrid.helpers.mail.objects.Setting;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.FailureType;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class SendGridEmailProvider implements NotificationProvider {

    private static final Logger log = LoggerFactory.getLogger(
            SendGridEmailProvider.class
    );

    private static final String PROVIDER_NAME = "sendgrid";
    private static final String ENDPOINT = "mail/send";

    private final SendGrid sendGrid;
    private final SendGridProperties properties;
    private final CircuitBreaker circuitBreaker;

    public SendGridEmailProvider(
            SendGrid sendGrid,
            SendGridProperties properties,
            CircuitBreakerRegistry circuitBreakerRegistry
    ) {
        this.sendGrid = sendGrid;
        this.properties = properties;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(PROVIDER_NAME);
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
    public NotificationSendResult send(
            ChannelMessage message,
            RecipientSnapshot recipient
    ) {
        try {
            return circuitBreaker.executeSupplier(() -> doSend(message, recipient));
        } catch (CallNotPermittedException e) {
            throw new NotificationDispatchException(
                    "CIRCUIT_OPEN",
                    "SendGrid circuit breaker abierto — demasiados fallos recientes",
                    e
            );
        }
    }

    private NotificationSendResult doSend(
            ChannelMessage message,
            RecipientSnapshot recipient
    ) {
        if (!(message instanceof EmailMessage emailMessage)) {
            throw new NotificationDispatchException(
                    "INVALID_EMAIL_MESSAGE",
                    "SendGridEmailProvider solo acepta EmailMessage"
            );
        }

        if (recipient.email() == null || recipient.email().isBlank()) {
            throw new NotificationDispatchException(
                    "MISSING_RECIPIENT_EMAIL",
                    "El destinatario no tiene email configurado"
            );
        }

        try {
            Mail mail = buildMail(emailMessage, recipient);
            Request request = buildRequest(mail);
            Response response = sendGrid.api(request);
            return handleResponse(response);

        } catch (NotificationDispatchException e) {
            throw e;
        } catch (IOException e) {
            throw new NotificationDispatchException(
                    "SENDGRID_IO_ERROR",
                    "Error de red al enviar email via SendGrid: " + e.getMessage(),
                    e
            );
        }
    }

    private Mail buildMail(
            EmailMessage emailMessage,
            RecipientSnapshot recipient
    ) {
        Email from = new Email(
                properties.fromEmail(),
                properties.fromName()
        );

        Email to = new Email(
                recipient.email(),
                recipient.displayName()
        );

        Content textContent = new Content(
                "text/plain",
                emailMessage.textBody()
        );

        Mail mail = new Mail(
                from,
                emailMessage.subject(),
                to,
                textContent
        );

        if (emailMessage.htmlBody() != null && !emailMessage.htmlBody().isBlank()) {
            mail.addContent(new Content("text/html", emailMessage.htmlBody()));
        }

        if (properties.sandboxMode()) {
            MailSettings mailSettings = new MailSettings();
            Setting sandbox = new Setting();
            sandbox.setEnable(true);
            mailSettings.setSandboxMode(sandbox);
            mail.setMailSettings(mailSettings);
        }

        return mail;
    }

    private Request buildRequest(Mail mail) throws IOException {
        Request request = new Request();
        request.setMethod(Method.POST);
        request.setEndpoint(ENDPOINT);
        request.setBody(mail.build());
        return request;
    }

    private NotificationSendResult handleResponse(Response response) {
        int statusCode = response.getStatusCode();

        if (statusCode == 202 || statusCode == 200) {
            String messageId = extractMessageId(response);
            return new NotificationSendResult(
                    PROVIDER_NAME,
                    NotificationChannel.EMAIL,
                    messageId,
                    AttemptStatus.ACCEPTED,
                    null,
                    null,
                    null
            );
        }

        String errorBody = response.getBody();
        log.warn("SendGrid respondió con status={} body={}", statusCode, errorBody);

        // Transitorio: 5xx (error de servidor) y 429 (rate limit)
        // Resilience4j cuenta la excepción como fallo → el circuit breaker puede abrirse
        // El dispatcher lo atrapa y aplica la retry policy
        if (statusCode >= 500 || statusCode == 429) {
            throw new NotificationDispatchException(
                    "SENDGRID_" + statusCode,
                    "SendGrid error transitorio (" + statusCode + "): " + errorBody
            );
        }

        // Permanente: otros 4xx (401, 403, 400, 422…)
        // Error del cliente → sin retry, sin contar en el circuit breaker
        return new NotificationSendResult(
                PROVIDER_NAME,
                NotificationChannel.EMAIL,
                null,
                AttemptStatus.FAILED,
                FailureType.PERMANENT,
                "SENDGRID_" + statusCode,
                errorBody
        );
    }

    private String extractMessageId(Response response) {
        String header = response.getHeaders().get("X-Message-Id");
        return header != null ? header : "sendgrid-" + System.currentTimeMillis();
    }
}