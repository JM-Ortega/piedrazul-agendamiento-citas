package co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.email.ses;

import co.edu.unicauca.piedrazul.backend.notifications.application.exception.NotificationDispatchException;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationChannel;
import co.edu.unicauca.piedrazul.backend.notifications.domain.message.SmsTextMessage;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.DeliveryOutcome;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.email.ses.LocalSmtpServer.Step;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.email.ses.SesSmtpTestKit.Harness;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.email.ses.LocalSmtpServer.closeNow;
import static co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.email.ses.LocalSmtpServer.reply;
import static co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.email.ses.SesSmtpTestKit.TO;
import static co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.email.ses.SesSmtpTestKit.email;
import static co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.email.ses.SesSmtpTestKit.recipient;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** El breaker cuenta fallos del proveedor, ignora rechazos del destinatario y evita conexiones. */
@Timeout(60)
class SesSmtpBreakerTest {

    private static final Duration READ = Duration.ofSeconds(2);
    private static final Duration DEADLINE = Duration.ofSeconds(5);
    private static final SesSmtpProperties.CircuitBreakerSettings SETTINGS =
            new SesSmtpProperties.CircuitBreakerSettings(2, 4, 50f, Duration.ofMillis(200), 2);

    private LocalSmtpServer server;
    private Harness harness;
    private final CircuitBreaker breaker = SesSmtpTestKit.breaker(SETTINGS);

    @AfterEach
    void close() throws Exception {
        try {
            if (harness != null) {
                harness.close();
            }
        } finally {
            if (server != null) {
                server.close();
            }
        }
    }

    /** Sustituye al emisor y al servidor anteriores, que quedarían fuera del alcance del cierre. */
    private void start(LocalSmtpServer configured) throws Exception {
        close();
        server = configured;
        harness = SesSmtpTestKit.harness(server, READ, DEADLINE, breaker, SmtpTransaction::new);
    }

    private DeliveryOutcome send() {
        return harness.provider().send(email(), recipient(TO)).outcome();
    }

    @Test
    void providerFaultsOpenTheCircuitAndAnOpenCircuitOpensNoConnection() throws Exception {
        start(new LocalSmtpServer().on(Step.GREETING, reply("421 unavailable")));

        send();
        send();

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        int connections = server.connections();

        assertThatThrownBy(this::send)
                .isInstanceOfSatisfying(NotificationDispatchException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(NotificationDispatchException.CIRCUIT_OPEN));
        assertThat(server.connections()).isEqualTo(connections);
    }

    @Test
    void anUnknownOutcomeCountsAgainstTheProvider() throws Exception {
        start(new LocalSmtpServer().on(Step.FINAL, closeNow()));

        assertThat(send()).isEqualTo(DeliveryOutcome.UNKNOWN);
        send();

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void authenticationAndSenderErrorsCountAgainstTheProvider() throws Exception {
        start(new LocalSmtpServer().on(Step.AUTH, reply("535 Authentication Credentials Invalid")));

        assertThat(send()).isEqualTo(DeliveryOutcome.PROVIDER_ERROR);
        send();

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void recipientRejectionsDoNotCount() throws Exception {
        start(new LocalSmtpServer().on(Step.RCPT, reply("550 No such user")));

        for (int i = 0; i < 6; i++) {
            assertThat(send()).isEqualTo(DeliveryOutcome.REJECTED);
        }

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker.getMetrics().getNumberOfBufferedCalls()).isZero();
    }

    @Test
    void temporaryRecipientRejectionsDoNotCountButAClosingServiceDoes() throws Exception {
        start(new LocalSmtpServer().on(Step.RCPT, reply("450 Mailbox busy")));

        send();
        send();
        assertThat(breaker.getMetrics().getNumberOfBufferedCalls()).isZero();

        start(new LocalSmtpServer().on(Step.RCPT, reply("421 closing")));
        send();
        send();
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void aMessageTooLongDoesNotCount() throws Exception {
        start(new LocalSmtpServer().on(Step.FINAL, reply("552 Message is too long.")));

        for (int i = 0; i < 6; i++) {
            assertThat(send()).isEqualTo(DeliveryOutcome.REJECTED);
        }

        assertThat(breaker.getMetrics().getNumberOfBufferedCalls()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "554 Access denied: User is not authorized to perform ses:SendRawEmail",
            "554 Message rejected: Email address is not verified.",
            "554 Account is paused",
    })
    void authorizationIdentityAndAccountRejectionsOpenTheCircuit(String reply) throws Exception {
        start(new LocalSmtpServer().on(Step.FINAL, reply(reply)));

        assertThat(send()).isEqualTo(DeliveryOutcome.PROVIDER_ERROR);
        send();

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        int connections = server.connections();
        assertThatThrownBy(this::send).isInstanceOf(NotificationDispatchException.class);
        assertThat(server.connections()).isEqualTo(connections);
    }

    @Test
    void aFinalReplyCutBeforeItsCrlfCountsAgainstTheProvider() throws Exception {
        start(new LocalSmtpServer().on(Step.FINAL, LocalSmtpServer.andThen(
                connection -> connection.write("250 Ok"), closeNow())));

        assertThat(send()).isEqualTo(DeliveryOutcome.UNKNOWN);
        send();

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void localRejectionsNeitherCountNorConnectNorConsumePermission() throws Exception {
        start(new LocalSmtpServer());
        breaker.transitionToOpenState();

        // Con el circuito abierto, lo que no llegaría a SES se rechaza igual, sin CIRCUIT_OPEN.
        assertThat(harness.provider().send(email(), recipient("ñandú@correo.example")).outcome())
                .isEqualTo(DeliveryOutcome.REJECTED);
        assertThat(harness.provider().send(new SmsTextMessage("hola"), recipient(TO)).outcome())
                .isEqualTo(DeliveryOutcome.REJECTED);
        assertThat(server.connections()).isZero();
        assertThat(breaker.getMetrics().getNumberOfBufferedCalls()).isZero();
        assertThat(harness.provider().channel()).isEqualTo(NotificationChannel.EMAIL);
    }

    @Test
    void afterTheWaitHalfOpenProbesCloseTheCircuitOnRecovery() throws Exception {
        start(new LocalSmtpServer().on(Step.GREETING, reply("421 unavailable")));
        send();
        send();
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // El mismo breaker, ahora con un servidor sano.
        start(new LocalSmtpServer());
        Thread.sleep(SETTINGS.openWait().multipliedBy(2));

        assertThat(send()).isEqualTo(DeliveryOutcome.ACCEPTED);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        assertThat(send()).isEqualTo(DeliveryOutcome.ACCEPTED);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void aFailedHalfOpenProbeReopensTheCircuit() throws Exception {
        start(new LocalSmtpServer().on(Step.GREETING, reply("421 unavailable")));
        send();
        send();
        Thread.sleep(SETTINGS.openWait().multipliedBy(2));

        send();
        send();

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void aNeutralResultDuringHalfOpenReleasesItsProbe() throws Exception {
        start(new LocalSmtpServer().on(Step.RCPT, reply("550 No such user")));
        breaker.transitionToOpenState();
        Thread.sleep(SETTINGS.openWait().multipliedBy(2));

        // Sin liberar el permiso, las sondas se agotarían y el circuito quedaría atascado.
        for (int i = 0; i < 6; i++) {
            assertThat(send()).isEqualTo(DeliveryOutcome.REJECTED);
        }

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
    }
}
