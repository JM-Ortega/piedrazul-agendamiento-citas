package co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.email.ses;

import co.edu.unicauca.piedrazul.backend.notifications.domain.model.AttemptStatus;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.FailureType;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.DeliveryOutcome;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.NotificationSendResult;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.email.ses.LocalSmtpServer.Step;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.email.ses.SesSmtpTestKit.Harness;
import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.URLName;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;

import static co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.email.ses.LocalSmtpServer.andThen;
import static co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.email.ses.LocalSmtpServer.closeNow;
import static co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.email.ses.LocalSmtpServer.reply;
import static co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.email.ses.LocalSmtpServer.resetNow;
import static co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.email.ses.LocalSmtpServer.stall;
import static co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.email.ses.SesSmtpTestKit.TO;
import static co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.email.ses.SesSmtpTestKit.email;
import static co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.email.ses.SesSmtpTestKit.harness;
import static co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.email.ses.SesSmtpTestKit.recipient;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Qué resultado da cada final de una conversación SMTP real (con STARTTLS) en cada etapa. Solo un
 * 250 bien formado a la secuencia final prueba la aceptación; solo un rechazo explícito o un fallo
 * anterior a la transacción prueba la no aceptación.
 */
@Timeout(60)
class SesSmtpClassificationTest {

    private static final Duration READ = Duration.ofMillis(800);
    private static final Duration DEADLINE = Duration.ofSeconds(10);

    private LocalSmtpServer server;
    private Harness harness;

    @AfterEach
    void close() throws Exception {
        if (harness != null) {
            harness.close();
        }

        if (server != null) {
            server.close();
        }
    }

    private LocalSmtpServer server() throws Exception {
        server = new LocalSmtpServer();
        return server;
    }

    private NotificationSendResult send() {
        harness = harness(server, READ, DEADLINE);
        return harness.provider().send(email(), recipient(TO));
    }

    // ── Aceptación ──────────────────────────────────────────────────────

    @Test
    void aFinal250IsAcceptedWithTheSesMessageId() throws Exception {
        server();

        NotificationSendResult result = send();

        assertThat(result.outcome()).isEqualTo(DeliveryOutcome.ACCEPTED);
        assertThat(result.status()).isEqualTo(AttemptStatus.ACCEPTED);
        assertThat(result.providerMessageId()).isEqualTo(LocalSmtpServer.SES_MESSAGE_ID);
        assertThat(result.errorCode()).isNull();
        assertThat(server.messages()).hasSize(1);
        assertThat(server.commands()).containsSubsequence(
                "STARTTLS", "AUTH", "MAIL FROM:<" + SesSmtpTestKit.FROM + ">", "RCPT TO:<" + TO + ">", "DATA");
    }

    @Test
    void aFinal250WithoutARecognizableIdIsAcceptedWithoutAnId() throws Exception {
        server().on(Step.FINAL, reply("250 Ok"));

        NotificationSendResult result = send();

        assertThat(result.outcome()).isEqualTo(DeliveryOutcome.ACCEPTED);
        assertThat(result.providerMessageId()).isNull();
    }

    @Test
    void acceptanceSurvivesAConnectionLostRightAfterTheFinalReply() throws Exception {
        server().on(Step.FINAL, andThen(reply(LocalSmtpServer.ACCEPTED_REPLY), resetNow()));

        assertThat(send().outcome()).isEqualTo(DeliveryOutcome.ACCEPTED);
    }

    @Test
    void acceptanceSurvivesAFailureAfterTheFinalReplyInsideTheSend() throws Exception {
        server();
        BiFunction<Session, URLName, SmtpTransaction> failingAfterAcceptance = (session, url) ->
                new SmtpTransaction(session, url) {
                    @Override
                    public synchronized void sendMessage(Message message, Address[] addresses)
                            throws MessagingException {
                        super.sendMessage(message, addresses);
                        throw new MessagingException("listener failed");
                    }
                };

        harness = harness(server, READ, DEADLINE, SesSmtpTestKit.breaker(), failingAfterAcceptance);

        assertThat(harness.provider().send(email(), recipient(TO)).outcome()).isEqualTo(DeliveryOutcome.ACCEPTED);
    }

    @Test
    void acceptanceSurvivesTheServerClosingRightAfterACompleteFinalReply() throws Exception {
        server().on(Step.FINAL, andThen(reply(LocalSmtpServer.ACCEPTED_REPLY), closeNow()));

        assertThat(send().outcome()).isEqualTo(DeliveryOutcome.ACCEPTED);
    }

    @Test
    void aFinalReplyWhoseCrlfArrivesInTwoRecordsIsComplete() throws Exception {
        server().on(Step.FINAL, connection -> {
            connection.write("250 Ok\r");
            Thread.sleep(50);
            connection.write("\n");
        });

        assertThat(send().outcome()).isEqualTo(DeliveryOutcome.ACCEPTED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"250 Ok", "250", "250 Ok\r", "250 Ok\n", "250-first\r\n250 last"})
    void aPositiveFinalReplyCutBeforeItsCrlfIsUnknown(String truncated) throws Exception {
        // Angus devuelve la línea parcial como si estuviera completa y dejaría pasar un 250.
        server().on(Step.FINAL, andThen(connection -> connection.write(truncated), closeNow()));

        assertUnknown(send());
        assertThat(server.connections()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"554 Message rejected", "451 Temporary service failure", "552 Message is too long."})
    void aNegativeFinalReplyCutBeforeItsCrlfDoesNotProveNonAcceptance(String truncated) throws Exception {
        server().on(Step.FINAL, andThen(connection -> connection.write(truncated), closeNow()));

        assertUnknown(send());
    }

    @Test
    void aFinalEofWithoutAnyReplyIsUnknown() throws Exception {
        server().on(Step.FINAL, closeNow());

        assertUnknown(send());
    }

    @Test
    void aFinalReplyCutBeforeItsCodeIsCompleteIsUnknown() throws Exception {
        server().on(Step.FINAL, andThen(connection -> connection.write("25"), closeNow()));

        assertUnknown(send());
    }

    @Test
    void aMultilineFinalReplyCutBeforeItsLastLineIsUnknown() throws Exception {
        server().on(Step.FINAL, andThen(connection -> connection.write("250-partial\r\n"), closeNow()));

        assertUnknown(send());
    }

    @Test
    void acceptanceSurvivesAFailureWhileClosingTheTransport() throws Exception {
        server();
        BiFunction<Session, URLName, SmtpTransaction> failingClose = (session, url) -> new SmtpTransaction(session, url) {
            @Override
            public synchronized void close() throws MessagingException {
                super.close();
                throw new MessagingException("cleanup failed");
            }
        };

        harness = harness(server, READ, DEADLINE, SesSmtpTestKit.breaker(), failingClose);

        assertThat(harness.provider().send(email(), recipient(TO)).outcome()).isEqualTo(DeliveryOutcome.ACCEPTED);
    }

    // ── Rechazo explícito por etapa ─────────────────────────────────────

    @ParameterizedTest(name = "{0} {1} → {2}")
    @CsvSource({
            "MAIL,  451, RETRYABLE,      SES_SMTP_MAIL_451",
            "MAIL,  553, PROVIDER_ERROR, SES_SMTP_MAIL_553",
            "MAIL,  530, PROVIDER_ERROR, SES_SMTP_MAIL_530",
            "RCPT,  450, RETRYABLE,      SES_SMTP_RCPT_450",
            "RCPT,  421, RETRYABLE,      SES_SMTP_RCPT_421",
            "RCPT,  550, REJECTED,       SES_SMTP_RCPT_550",
            "RCPT,  553, REJECTED,       SES_SMTP_RCPT_553",
            "RCPT,  554, PROVIDER_ERROR, SES_SMTP_RCPT_554",
            "RCPT,  535, PROVIDER_ERROR, SES_SMTP_RCPT_535",
            "DATA,  451, RETRYABLE,      SES_SMTP_DATA_451",
            "DATA,  554, PROVIDER_ERROR, SES_SMTP_DATA_554",
            "FINAL, 454, RETRYABLE,      SES_SMTP_FINAL_REPLY_454",
            "FINAL, 451, RETRYABLE,      SES_SMTP_FINAL_REPLY_451",
            "FINAL, 552, REJECTED,       SES_SMTP_FINAL_REPLY_552",
            "FINAL, 554, PROVIDER_ERROR, SES_SMTP_FINAL_REPLY_554",
            "FINAL, 550, PROVIDER_ERROR, SES_SMTP_FINAL_REPLY_550",
    })
    void explicitRejectionsAreClassifiedByStage(Step step, int code, DeliveryOutcome outcome, String errorCode)
            throws Exception {
        // El texto incluye un valor sensible: nada de él debe salir del proveedor.
        server().on(step, reply(code + " rechazado para " + TO + " codigo 000111"));

        NotificationSendResult result = send();

        assertThat(result.outcome()).isEqualTo(outcome);
        assertThat(result.errorCode()).isEqualTo(errorCode);
        assertThat(result.errorMessage()).doesNotContain(TO).doesNotContain("000111");
        assertThat(result.failureType())
                .isEqualTo(outcome == DeliveryOutcome.RETRYABLE ? FailureType.TEMPORARY : FailureType.PERMANENT);
    }

    @ParameterizedTest
    @EnumSource(value = Step.class, names = {"MAIL", "RCPT", "DATA", "FINAL"})
    void anExplicitRejectionSurvivesAConnectionLostDuringTheFollowingReset(Step step) throws Exception {
        server().on(step, andThen(reply("550 rechazado"), resetNow()));

        assertThat(send().outcome()).isNotEqualTo(DeliveryOutcome.UNKNOWN);
    }

    // ── Aceptación desconocida ───────────────────────────────────────────

    @ParameterizedTest
    @EnumSource(value = Step.class, names = {"MAIL", "RCPT", "DATA", "BODY", "FINAL"})
    void aClosedConnectionDuringTheTransactionIsUnknown(Step step) throws Exception {
        server().on(step, closeNow());

        assertUnknown(send());
    }

    @ParameterizedTest
    @EnumSource(value = Step.class, names = {"MAIL", "RCPT", "DATA", "BODY", "FINAL"})
    void aResetDuringTheTransactionIsUnknown(Step step) throws Exception {
        server().on(step, resetNow());

        assertUnknown(send());
    }

    @ParameterizedTest
    @EnumSource(value = Step.class, names = {"MAIL", "RCPT", "DATA", "FINAL"})
    void aReadTimeoutDuringTheTransactionIsUnknown(Step step) throws Exception {
        server().on(step, stall());

        assertUnknown(send());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "2500 Ok",
            "25O Ok",
            "250Ok",
            "ok",
            "251 Ok",
            "200 Ok",
            "354 go on",
            "250-partial\r\n550 mixed",
            "550-partial\r\n250 mixed",
    })
    void aFinalReplyThatIsNotAWellFormed250OrRejectionIsUnknown(String reply) throws Exception {
        server().on(Step.FINAL, reply(reply));

        assertUnknown(send());
        assertThat(server.connections()).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(value = Step.class, names = {"MAIL", "RCPT", "DATA"})
    void aMalformedReplyBeforeTheBodyIsUnknown(Step step) throws Exception {
        server().on(step, reply("xyz whatever"));

        assertUnknown(send());
    }

    @Test
    void aMalformed354NeverSendsTheBodyAndIsUnknown() throws Exception {
        List<String> receivedAfterData = new CopyOnWriteArrayList<>();

        server().on(Step.DATA, andThen(reply("3540 go"), connection -> {
            for (String line = connection.readLine(); line != null; line = connection.readLine()) {
                receivedAfterData.add(line);
            }
        }));

        assertUnknown(send());
        server.awaitClientDisconnect(Duration.ofSeconds(5));
        assertThat(receivedAfterData).noneMatch(line -> line.startsWith("Subject:") || line.equals("."));
    }

    @Test
    void intermediateSuccessIsNotProofOfAcceptance() throws Exception {
        // MAIL, RCPT y DATA responden bien; el cuerpo entero llega; falta la respuesta final.
        server().on(Step.FINAL, stall());

        NotificationSendResult result = send();

        assertUnknown(result);
        assertThat(server.messages()).hasSize(1);
    }

    @Test
    void angusNotDeliveredIndicationsDoNotProveNonAcceptanceWhenTheFinalReplyIsLost() throws Exception {
        // Angus lo notifica como MESSAGE_NOT_DELIVERED con SMTPSendFailedException, código -1.
        server().on(Step.FINAL, closeNow());

        NotificationSendResult result = send();

        assertThat(result.outcome()).isEqualTo(DeliveryOutcome.UNKNOWN);
        assertThat(result.errorCode()).isEqualTo(SmtpFailureClassifier.UNCONFIRMED);
    }

    // ── Respuestas documentadas por SES ─────────────────────────────────

    @ParameterizedTest(name = "{1} → {2}")
    @CsvSource(delimiter = '|', value = {
            "FINAL | 554 Access denied: User arn:aws:iam::000000000000:user/ses is not authorized to perform ses:SendRawEmail on resource arn:aws:ses:us-east-1:000000000000:identity/piedrazul.example | PROVIDER_ERROR",
            "FINAL | 554 Message rejected: Email address is not verified. The following identities failed the check in region US-EAST-1: no-reply@piedrazul.example | PROVIDER_ERROR",
            "FINAL | 554 Account is paused | PROVIDER_ERROR",
            "FINAL | 552 Message is too long. | REJECTED",
            "FINAL | 454 Throttling failure: Daily message quota exceeded | RETRYABLE",
            "FINAL | 454 Throttling failure: Maximum sending rate exceeded | RETRYABLE",
            "FINAL | 451 Temporary service failure | RETRYABLE",
            "MAIL  | 553 <remitente> Invalid email address | PROVIDER_ERROR",
            "RCPT  | 553 <destinatario> address unknown | REJECTED",
            "AUTH  | 535 Account not subscribed to SES | PROVIDER_ERROR",
            "AUTH  | 454 Temporary authentication failure | RETRYABLE",
            "MAIL  | 530 Authentication required | PROVIDER_ERROR",
    })
    void documentedSesRepliesKeepTheirMeaning(Step step, String reply, DeliveryOutcome outcome) throws Exception {
        server().on(step, reply(reply));

        NotificationSendResult result = send();

        assertThat(result.outcome()).isEqualTo(outcome);
        assertThat(result.errorMessage()).doesNotContain("arn:aws").doesNotContain("piedrazul.example");
    }

    private static void assertUnknown(NotificationSendResult result) {
        assertThat(result.outcome()).isEqualTo(DeliveryOutcome.UNKNOWN);
        assertThat(result.status()).isEqualTo(AttemptStatus.UNKNOWN);
        assertThat(result.failureType()).isEqualTo(FailureType.UNKNOWN);
        assertThat(result.providerMessageId()).isNull();
    }

    // ── Antes de la transacción ─────────────────────────────────────────

    @Test
    void aRefusedConnectionIsRetryable() throws Exception {
        server();
        int port = server.port();
        harness = harness(server, READ, DEADLINE);
        server.close();

        assertThat(port).isPositive();
        NotificationSendResult result = harness.provider().send(email(), recipient(TO));

        assertThat(result.outcome()).isEqualTo(DeliveryOutcome.RETRYABLE);
        assertThat(result.errorCode()).isEqualTo("SES_SMTP_CONNECT_FAILED");
    }

    @Test
    void aClosedGreetingIsRetryable() throws Exception {
        server().on(Step.GREETING, closeNow());

        assertThat(send().outcome()).isEqualTo(DeliveryOutcome.RETRYABLE);
    }

    @Test
    void aSilentGreetingIsRetryable() throws Exception {
        server().on(Step.GREETING, stall());

        assertThat(send().outcome()).isEqualTo(DeliveryOutcome.RETRYABLE);
    }

    @Test
    void aTemporaryGreetingRejectionIsRetryable() throws Exception {
        server().on(Step.GREETING, reply("421 too busy"));

        NotificationSendResult result = send();

        assertThat(result.outcome()).isEqualTo(DeliveryOutcome.RETRYABLE);
        assertThat(result.errorCode()).isEqualTo("SES_SMTP_SESSION_421");
    }

    @ParameterizedTest
    @CsvSource({"454, RETRYABLE, SES_SMTP_SESSION_454", "502, PROVIDER_ERROR, SES_SMTP_SESSION_502"})
    void aStartTlsRefusalIsClassifiedByItsCode(int code, DeliveryOutcome outcome, String errorCode) throws Exception {
        server().on(Step.STARTTLS, reply(code + " TLS not available"));

        NotificationSendResult result = send();

        assertThat(result.outcome()).isEqualTo(outcome);
        assertThat(result.errorCode()).isEqualTo(errorCode);
        assertThat(server.commands()).doesNotContain("AUTH");
    }

    @Test
    void aConnectionClosedDuringTheTlsHandshakeIsATransientFailure() throws Exception {
        server().on(Step.STARTTLS, andThen(reply("220 ready"), closeNow()));

        NotificationSendResult result = send();

        assertThat(result.outcome()).isEqualTo(DeliveryOutcome.RETRYABLE);
        assertThat(result.errorCode()).isEqualTo("SES_SMTP_TLS_INTERRUPTED");
    }

    @Test
    void aConnectionResetDuringTheTlsHandshakeIsATransientFailure() throws Exception {
        server().on(Step.STARTTLS, andThen(reply("220 ready"), resetNow()));

        NotificationSendResult result = send();

        assertThat(result.outcome()).isEqualTo(DeliveryOutcome.RETRYABLE);
        assertThat(result.errorCode()).isEqualTo("SES_SMTP_TLS_INTERRUPTED");
    }

    @Test
    void aPeerThatDoesNotSpeakTlsIsAPolicyFailure() throws Exception {
        server().on(Step.STARTTLS, andThen(reply("220 ready"), andThen(
                connection -> connection.write("this peer does not negotiate TLS\r\n"), stall())));

        NotificationSendResult result = send();

        assertThat(result.outcome()).isEqualTo(DeliveryOutcome.PROVIDER_ERROR);
        assertThat(result.errorCode()).isEqualTo("SES_SMTP_TLS_POLICY");
    }

    @Test
    void aConnectionClosedDuringAuthenticationIsNotARejectionOfTheCredentials() throws Exception {
        server().on(Step.AUTH, closeNow());

        NotificationSendResult result = send();

        assertThat(result.outcome()).isEqualTo(DeliveryOutcome.RETRYABLE);
        assertThat(result.errorCode()).isEqualTo("SES_SMTP_CONNECT_FAILED");
    }

    @Test
    void aConnectionResetDuringAuthenticationIsATransientFailure() throws Exception {
        server().on(Step.AUTH, resetNow());

        assertThat(send().outcome()).isEqualTo(DeliveryOutcome.RETRYABLE);
    }

    @Test
    void aRejectedEhloRecoveredByHeloDoesNotCountAsTheLaterFailure() throws Exception {
        // HELO resuelve el 502 del EHLO; lo que falla después es la ausencia de STARTTLS.
        server().on(Step.EHLO, reply("502 not implemented"));

        NotificationSendResult result = send();

        assertThat(result.outcome()).isEqualTo(DeliveryOutcome.PROVIDER_ERROR);
        assertThat(result.errorCode()).isEqualTo("SES_SMTP_SESSION_REFUSED");
    }

    @Test
    void aServerWithoutStartTlsIsAProviderErrorAndGetsNoCredentials() throws Exception {
        server().on(Step.EHLO, reply("250-local\r\n250 AUTH PLAIN LOGIN"));

        NotificationSendResult result = send();

        assertThat(result.outcome()).isEqualTo(DeliveryOutcome.PROVIDER_ERROR);
        assertThat(result.errorCode()).isEqualTo("SES_SMTP_SESSION_REFUSED");
        assertThat(server.commands()).doesNotContain("AUTH", "STARTTLS");
    }

    @Test
    void anUntrustedCertificateIsAProviderErrorAndGetsNoCredentials() throws Exception {
        server = new LocalSmtpServer(LocalSmtpServer.Tls.forHost("untrusted.local"));

        NotificationSendResult result = send();

        assertThat(result.outcome()).isEqualTo(DeliveryOutcome.PROVIDER_ERROR);
        assertThat(result.errorCode()).isEqualTo("SES_SMTP_TLS_CERTIFICATE");
        assertThat(server.commands()).doesNotContain("AUTH");
    }

    @Test
    void aTrustedCertificateForAnotherHostIsAProviderError() throws Exception {
        // Confía en el certificado de "wrong.local", pero se conecta a "localhost".
        LocalSmtpServer.Tls wrongHost = LocalSmtpServer.Tls.forHost("wrong.local");
        server = new LocalSmtpServer(wrongHost);
        SesSmtpProperties properties = SesSmtpTestKit.properties(
                server.port(), READ, Duration.ofSeconds(5), DEADLINE);
        SesSmtpSender sender = new SesSmtpSender(properties, wrongHost.client());

        try {
            SesEmailProvider provider = new SesEmailProvider(sender, properties, SesSmtpTestKit.breaker(),
                    new co.edu.unicauca.piedrazul.backend.notifications.application.RecipientEmailPolicy(
                            SesSmtpTestKit.VALIDATOR));
            NotificationSendResult result = provider.send(email(), recipient(TO));

            assertThat(result.outcome()).isEqualTo(DeliveryOutcome.PROVIDER_ERROR);
            assertThat(result.errorCode()).isEqualTo("SES_SMTP_TLS_CERTIFICATE");
            assertThat(server.commands()).doesNotContain("AUTH");
        } finally {
            sender.destroy();
        }
    }

    @Test
    void rejectedCredentialsAreAProviderError() throws Exception {
        server().on(Step.AUTH, reply("535 Authentication Credentials Invalid"));

        NotificationSendResult result = send();

        assertThat(result.outcome()).isEqualTo(DeliveryOutcome.PROVIDER_ERROR);
        assertThat(result.errorCode()).isEqualTo("SES_SMTP_AUTH_FAILED");
    }

    @Test
    void aTemporaryAuthenticationFailureIsRetryable() throws Exception {
        server().on(Step.AUTH, reply("454 Temporary authentication failure"));

        assertThat(send().outcome()).isEqualTo(DeliveryOutcome.RETRYABLE);
    }

    @Test
    void aServerThatDoesNotOfferAuthenticationReceivesNoMessage() throws Exception {
        server().withoutAuth();

        NotificationSendResult result = send();

        assertThat(result.outcome()).isEqualTo(DeliveryOutcome.PROVIDER_ERROR);
        assertThat(server.commands()).noneMatch(command -> command.startsWith("MAIL"));
    }
}
