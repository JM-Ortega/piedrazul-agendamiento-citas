package co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.email.ses;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import co.edu.unicauca.piedrazul.backend.notifications.domain.message.EmailMessage;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.DeliveryOutcome;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.NotificationSendResult;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.email.ses.LocalSmtpServer.Step;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.email.ses.SesSmtpTestKit.Harness;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.URLName;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.eclipse.angus.mail.smtp.SMTPTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.email.ses.LocalSmtpServer.reply;
import static co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.email.ses.SesSmtpTestKit.FROM;
import static co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.email.ses.SesSmtpTestKit.PASSWORD;
import static co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.email.ses.SesSmtpTestKit.TO;
import static co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.email.ses.SesSmtpTestKit.harness;
import static co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.email.ses.SesSmtpTestKit.recipient;
import static org.assertj.core.api.Assertions.assertThat;

/** El mensaje que llega al servidor y lo que no debe quedar en ningún log. */
@Timeout(60)
class SesEmailProviderMessageTest {

    private LocalSmtpServer server;
    private Harness harness;

    @BeforeEach
    void start() throws Exception {
        server = new LocalSmtpServer();
        harness = harness(server, Duration.ofSeconds(5), Duration.ofSeconds(10));
    }

    @AfterEach
    void close() throws Exception {
        harness.close();
        server.close();
    }

    private MimeMessage delivered() throws Exception {
        assertThat(server.messages()).hasSize(1);
        return new MimeMessage(Session.getInstance(new Properties()),
                new ByteArrayInputStream(server.messages().getFirst()));
    }

    @Test
    void headersAndBodiesArriveIntactInUtf8() throws Exception {
        String subject = "Código de verificación — ñandú";
        String html = "<p>Tu código es <b>000111</b>, señor Peña</p>";
        String text = "Tu código es 000111, señor Peña";

        NotificationSendResult result = harness.provider()
                .send(new EmailMessage(subject, html, text), recipient(TO));

        assertThat(result.outcome()).isEqualTo(DeliveryOutcome.ACCEPTED);
        MimeMessage message = delivered();

        assertThat(message.getSubject()).isEqualTo(subject);
        assertThat(((InternetAddress) message.getFrom()[0]).getAddress()).isEqualTo(FROM);
        assertThat(((InternetAddress) message.getFrom()[0]).getPersonal()).isEqualTo("Piedrazul Salud");
        assertThat(message.getContentType()).startsWith("multipart/alternative");

        MimeMultipart parts = (MimeMultipart) message.getContent();
        assertThat(parts.getCount()).isEqualTo(2);
        assertThat(parts.getBodyPart(0).getContentType()).contains("text/plain").containsIgnoringCase("utf-8");
        assertThat(parts.getBodyPart(0).getContent()).isEqualTo(text);
        assertThat(parts.getBodyPart(1).getContentType()).contains("text/html").containsIgnoringCase("utf-8");
        assertThat(parts.getBodyPart(1).getContent()).isEqualTo(html);
    }

    @Test
    void theRecipientHeaderCarriesTheAddressButNotTheDisplayName() throws Exception {
        harness.provider().send(SesSmtpTestKit.email(), recipient(TO));

        InternetAddress[] to = (InternetAddress[]) delivered().getRecipients(Message.RecipientType.TO);

        assertThat(to).hasSize(1);
        assertThat(to[0].getAddress()).isEqualTo(TO);
        assertThat(to[0].getPersonal()).isNull();
    }

    @Test
    void aTextOnlyMessageIsNotMultipart() throws Exception {
        harness.provider().send(new EmailMessage("Asunto", null, "Solo texto"), recipient(TO));

        assertThat(delivered().getContentType()).startsWith("text/plain");
        assertThat(delivered().getContent().toString()).isEqualToIgnoringWhitespace("Solo texto");
    }

    @Test
    void theEnvelopeUsesThePunycodeFormOfAnInternationalizedDomain() throws Exception {
        harness.provider().send(SesSmtpTestKit.email(), recipient("ana@clínica.example"));

        assertThat(server.commands()).contains("RCPT TO:<ana@xn--clnica-4va.example>");
        assertThat(delivered().getRecipients(Message.RecipientType.TO)[0].toString())
                .isEqualTo("ana@xn--clnica-4va.example");
    }

    @Test
    void theClientIdentityIsTheLibraryDefaultAndNotTheSenderDomain() throws Exception {
        String libraryDefault = new SMTPTransport(Session.getInstance(new Properties()), new URLName("smtp://x"))
                .getLocalHost();

        harness.provider().send(SesSmtpTestKit.email(), recipient(TO));

        assertThat(server.commands()).filteredOn(command -> command.startsWith("EHLO"))
                .hasSize(2)
                .containsOnly("EHLO " + libraryDefault);
        assertThat(libraryDefault).isNotEqualTo("piedrazul.example");
    }

    @Test
    void removingTheClientIdentityOverrideKeepsSenderAndEnvelope() throws Exception {
        harness.provider().send(SesSmtpTestKit.email(), recipient(TO));

        assertThat(server.commands()).contains("MAIL FROM:<" + FROM + ">", "RCPT TO:<" + TO + ">");
        assertThat(((InternetAddress) delivered().getFrom()[0]).getAddress()).isEqualTo(FROM);
        assertThat(delivered().getMessageID()).isNotBlank();
    }

    @Test
    void aSubjectWithLineBreaksIsRejectedLocallyWithoutConnecting() {
        NotificationSendResult result = harness.provider().send(
                new EmailMessage("Asunto\r\nBcc: tercero@correo.example", "<p>x</p>", "x"), recipient(TO));

        assertThat(result.outcome()).isEqualTo(DeliveryOutcome.REJECTED);
        assertThat(server.connections()).isZero();
    }

    @Test
    void anEmptyMessageIsRejectedLocallyWithoutConnecting() {
        NotificationSendResult result = harness.provider().send(new EmailMessage("Asunto", " ", null), recipient(TO));

        assertThat(result.outcome()).isEqualTo(DeliveryOutcome.REJECTED);
        assertThat(server.connections()).isZero();
    }

    @Test
    void anUnusableRecipientIsRejectedLocallyWithoutConnecting() {
        for (String email : new String[]{"ñandú@correo.example", "ana@faß.example", "ana@ejemplo.invalid", null}) {
            NotificationSendResult result = harness.provider().send(SesSmtpTestKit.email(), recipient(email));

            assertThat(result.outcome()).isEqualTo(DeliveryOutcome.REJECTED);
            assertThat(result.errorCode()).isEqualTo("SES_RECIPIENT_UNDELIVERABLE");
        }

        assertThat(server.connections()).isZero();
    }

    @Test
    void protocolLoggingStaysOffEvenWithTheMostDetailedGlobalLevels() throws Exception {
        Logger julRoot = Logger.getLogger("");
        java.util.logging.Level previousJul = julRoot.getLevel();
        ch.qos.logback.classic.Logger logbackRoot =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        Level previousLogback = logbackRoot.getLevel();
        ListAppender<ILoggingEvent> logback = new ListAppender<>();
        List<String> jul = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                jul.add(record.getLoggerName() + " " + record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        handler.setLevel(java.util.logging.Level.ALL);

        PrintStream previousOut = System.out;
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();

        try {
            julRoot.setLevel(java.util.logging.Level.ALL);
            julRoot.addHandler(handler);
            logbackRoot.setLevel(Level.TRACE);
            logback.start();
            logbackRoot.addAppender(logback);
            System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));

            server.on(Step.RCPT, reply("550 rechazado para " + TO));
            harness.provider().send(new EmailMessage("Asunto", "<p>000111</p>", "000111"), recipient(TO));
            server.on(Step.RCPT, reply("250 Ok"));
            harness.provider().send(new EmailMessage("Asunto", "<p>000111</p>", "000111"), recipient(TO));
        } finally {
            System.setOut(previousOut);
            julRoot.removeHandler(handler);
            julRoot.setLevel(previousJul);
            logbackRoot.detachAppender(logback);
            logbackRoot.setLevel(previousLogback);
        }

        List<String> everything = new ArrayList<>(jul);
        logback.list.forEach(event -> everything.add(event.getLoggerName() + " " + event.getFormattedMessage()));
        everything.add(stdout.toString(StandardCharsets.UTF_8));

        assertThat(everything).noneMatch(line -> line.contains("000111"));
        assertThat(everything).noneMatch(line -> line.contains(PASSWORD));
        assertThat(everything).noneMatch(line -> line.contains(TO));
    }

    @Test
    void theTransportLoggerIsPinnedBecauseAngusNamesItAfterTheSubclass() {
        Logger transportLogger = Logger.getLogger(SmtpTransaction.class.getPackageName());

        new SmtpTransaction(Session.getInstance(new Properties()), null);

        assertThat(transportLogger.isLoggable(java.util.logging.Level.INFO)).isFalse();
        assertThat(Logger.getLogger(SmtpTransaction.class.getPackageName() + ".protocol")
                .isLoggable(java.util.logging.Level.FINEST)).isFalse();
    }
}
