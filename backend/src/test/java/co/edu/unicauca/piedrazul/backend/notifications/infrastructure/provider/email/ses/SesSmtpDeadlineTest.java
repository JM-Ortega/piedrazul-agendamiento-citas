package co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.email.ses;

import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.DeliveryOutcome;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.NotificationSendResult;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.email.ses.LocalSmtpServer.Step;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.email.ses.SesSmtpTestKit.Harness;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.URLName;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import static co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.email.ses.LocalSmtpServer.delayedReply;
import static co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.email.ses.LocalSmtpServer.dripForever;
import static co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.email.ses.LocalSmtpServer.reply;
import static co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.email.ses.LocalSmtpServer.stall;
import static co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.email.ses.SesSmtpTestKit.TO;
import static co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.email.ses.SesSmtpTestKit.email;
import static co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.email.ses.SesSmtpTestKit.harness;
import static co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.email.ses.SesSmtpTestKit.recipient;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * El plazo total corta la E/S real del socket, sin depender del hilo que ejecuta el envío ni del
 * planificador, incluso cuando cada operación progresa y ningún tiempo por operación vence. El
 * bloqueo dura lo que el servidor quiera, así que los tiempos largos de lectura hacen que solo
 * el plazo total pueda terminarlo.
 */
@Timeout(60)
class SesSmtpDeadlineTest {

    private static final Duration LONG_READ = Duration.ofSeconds(15);
    private static final Duration DEADLINE = Duration.ofMillis(700);
    private static final Duration WITHIN_BOUND = Duration.ofSeconds(5);

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

    private NotificationSendResult send(Duration read, Duration deadline) {
        harness = harness(server, read, deadline);
        return harness.provider().send(email(), recipient(TO));
    }

    // ── Terminación efectiva ────────────────────────────────────────────

    @Test
    void aBlockedGreetingOnThePlainSocketIsTerminatedAsKnownNonAcceptance() throws Exception {
        server().on(Step.GREETING, stall());

        NotificationSendResult result = assertBounded(() -> send(LONG_READ, DEADLINE));

        assertThat(result.outcome()).isEqualTo(DeliveryOutcome.RETRYABLE);
        assertThat(result.errorCode()).isEqualTo(SmtpFailureClassifier.DEADLINE_EXCEEDED);
        assertThat(server.awaitClientDisconnect(WITHIN_BOUND)).isTrue();
    }

    @Test
    void aBlockedAuthenticationInsideTlsIsTerminated() throws Exception {
        server().on(Step.AUTH, stall());

        NotificationSendResult result = assertBounded(() -> send(LONG_READ, DEADLINE));

        assertThat(result.outcome()).isEqualTo(DeliveryOutcome.RETRYABLE);
        assertThat(result.errorCode()).isEqualTo(SmtpFailureClassifier.DEADLINE_EXCEEDED);
        assertThat(server.awaitClientDisconnect(WITHIN_BOUND)).isTrue();
    }

    @Test
    void aBlockedTlsHandshakeIsTerminated() throws Exception {
        // Acepta STARTTLS pero no negocia: el bloqueo ocurre dentro de startHandshake.
        try (ServerSocket silent = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            Thread peer = Thread.ofPlatform().daemon().start(() -> playSilentStartTls(silent));
            server = new LocalSmtpServer();
            SesSmtpProperties properties = SesSmtpTestKit.properties(
                    silent.getLocalPort(), LONG_READ, Duration.ofSeconds(5), DEADLINE);
            SesSmtpSender sender = new SesSmtpSender(properties, LocalSmtpServer.Tls.forHost("localhost").client());
            SesEmailProvider provider = new SesEmailProvider(sender, properties, SesSmtpTestKit.breaker(),
                    new co.edu.unicauca.piedrazul.backend.notifications.application.RecipientEmailPolicy(
                            SesSmtpTestKit.VALIDATOR));

            try {
                NotificationSendResult result = assertBounded(() -> provider.send(email(), recipient(TO)));

                assertThat(result.outcome()).isEqualTo(DeliveryOutcome.RETRYABLE);
                assertThat(result.errorCode()).isEqualTo(SmtpFailureClassifier.DEADLINE_EXCEEDED);
            } finally {
                sender.destroy();
                peer.join(WITHIN_BOUND);
            }
        }
    }

    private static void playSilentStartTls(ServerSocket silent) {
        try (Socket socket = silent.accept()) {
            socket.setSoTimeout((int) WITHIN_BOUND.toMillis());
            var out = socket.getOutputStream();
            var in = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream()));
            out.write("220 local\r\n".getBytes());
            in.readLine();
            out.write("250-local\r\n250 STARTTLS\r\n".getBytes());
            in.readLine();
            out.write("220 ready\r\n".getBytes());
            socket.getInputStream().read();
            while (socket.getInputStream().read() != -1) {
                // Descarta el ClientHello sin responder.
            }
        } catch (IOException exception) {
            // El cliente cortó: es lo esperado.
        }
    }

    @ParameterizedTest
    @EnumSource(value = Step.class, names = {"MAIL", "RCPT", "DATA", "FINAL"})
    void aBlockedReadDuringTheTransactionIsTerminatedAsUnknown(Step step) throws Exception {
        server().on(step, stall());

        NotificationSendResult result = assertBounded(() -> send(LONG_READ, DEADLINE));

        assertThat(result.outcome()).isEqualTo(DeliveryOutcome.UNKNOWN);
        assertThat(result.errorCode()).isEqualTo(SmtpFailureClassifier.DEADLINE_EXCEEDED);
        assertThat(server.awaitClientDisconnect(WITHIN_BOUND)).isTrue();
    }

    @Test
    void aDrippedReplyDefeatsTheReadTimeoutButNotTheDeadline() throws Exception {
        // Cada byte reinicia SO_TIMEOUT; solo el plazo total puede terminar esta lectura.
        server().on(Step.FINAL, dripForever(Duration.ofMillis(50)));

        NotificationSendResult result = assertBounded(() -> send(Duration.ofMillis(300), DEADLINE));

        assertThat(result.outcome()).isEqualTo(DeliveryOutcome.UNKNOWN);
        assertThat(result.errorCode()).isEqualTo(SmtpFailureClassifier.DEADLINE_EXCEEDED);
    }

    @Test
    void consecutiveSlowStagesAddUpToTheDeadline() throws Exception {
        // Ninguna etapa supera la lectura de 500 ms; juntas superan el plazo de 1 s.
        Duration step = Duration.ofMillis(350);

        server().on(Step.MAIL, delayedReply(step, "250 Ok"))
                .on(Step.RCPT, delayedReply(step, "250 Ok"))
                .on(Step.DATA, delayedReply(step, "354 go"))
                .on(Step.BODY, stall());

        NotificationSendResult result = assertBounded(() -> send(Duration.ofMillis(500), Duration.ofSeconds(1)));

        assertThat(result.outcome()).isEqualTo(DeliveryOutcome.UNKNOWN);
        assertThat(result.errorCode()).isEqualTo(SmtpFailureClassifier.DEADLINE_EXCEEDED);
    }

    @Test
    void anUnresponsivePeerDuringConnectIsBounded() throws Exception {
        // Cola de aceptación llena: el SYN no se completa y connect queda a la espera.
        try (ServerSocket full = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            List<Socket> fillers = new ArrayList<>();

            try {
                for (int i = 0; i < 4; i++) {
                    Socket filler = new Socket();
                    try {
                        filler.connect(full.getLocalSocketAddress(), 200);
                        fillers.add(filler);
                    } catch (IOException exception) {
                        filler.close();
                        break;
                    }
                }

                server = new LocalSmtpServer();
                SesSmtpProperties properties = SesSmtpTestKit.properties(
                        full.getLocalPort(), LONG_READ, Duration.ofSeconds(5), DEADLINE);
                SesSmtpSender sender = new SesSmtpSender(properties, LocalSmtpServer.Tls.forHost("localhost").client());
                SesEmailProvider provider = new SesEmailProvider(sender, properties, SesSmtpTestKit.breaker(),
                        new co.edu.unicauca.piedrazul.backend.notifications.application.RecipientEmailPolicy(
                                SesSmtpTestKit.VALIDATOR));

                try {
                    NotificationSendResult result = assertBounded(() -> provider.send(email(), recipient(TO)));

                    assertThat(result.outcome()).isEqualTo(DeliveryOutcome.RETRYABLE);
                } finally {
                    sender.destroy();
                }
            } finally {
                for (Socket filler : fillers) {
                    filler.close();
                }
            }
        }
    }

    // ── Carreras ────────────────────────────────────────────────────────

    @Test
    void anAcceptanceReadBeforeTheDeadlineIsKeptWhenTheDeadlineFiresAfterwards() throws Exception {
        server();
        CountDownLatch finished = new CountDownLatch(1);

        // Retiene el hilo del envío justo tras leer el 250 hasta que el plazo ha vencido.
        BiFunction<Session, URLName, SmtpTransaction> slowAfterAcceptance = (session, url) ->
                new SmtpTransaction(session, url) {
                    @Override
                    protected void finishData() throws IOException, MessagingException {
                        super.finishData();
                        sleep(DEADLINE.multipliedBy(2));
                        finished.countDown();
                    }
                };

        harness = harness(server, LONG_READ, DEADLINE, SesSmtpTestKit.breaker(), slowAfterAcceptance);
        NotificationSendResult result = harness.provider().send(email(), recipient(TO));

        assertThat(finished.getCount()).isZero();
        assertThat(result.outcome()).isEqualTo(DeliveryOutcome.ACCEPTED);
        assertThat(result.providerMessageId()).isEqualTo(LocalSmtpServer.SES_MESSAGE_ID);
    }

    @Test
    void aFinalReplyArrivingAfterTheDeadlineIsUnknownEvenIfTheServerAccepted() throws Exception {
        server().on(Step.FINAL, delayedReply(DEADLINE.multipliedBy(2), LocalSmtpServer.ACCEPTED_REPLY));

        NotificationSendResult result = send(LONG_READ, DEADLINE);

        assertThat(result.outcome()).isEqualTo(DeliveryOutcome.UNKNOWN);
        assertThat(server.messages()).hasSize(1);
    }

    @Test
    void repeatedRacesBetweenFinalReplyAndDeadlineNeverClaimNonAcceptance() throws Exception {
        // El 250 llega alrededor del vencimiento: cualquiera de los dos puede ganar.
        Duration deadline = Duration.ofMillis(150);
        AtomicInteger delay = new AtomicInteger();

        server().on(Step.FINAL, connection -> {
            Thread.sleep(delay.getAndUpdate(value -> (value + 7) % 60));
            connection.write(LocalSmtpServer.ACCEPTED_REPLY + "\r\n");
        });

        harness = harness(server, LONG_READ, deadline.plusMillis(0));
        List<DeliveryOutcome> outcomes = new ArrayList<>();

        for (int i = 0; i < 25; i++) {
            outcomes.add(harness.provider().send(email(), recipient(TO)).outcome());
        }

        assertThat(outcomes).allMatch(outcome ->
                outcome == DeliveryOutcome.ACCEPTED || outcome == DeliveryOutcome.UNKNOWN);
    }

    @Test
    void aFinalReplyCutWhileTheDeadlineExpiresIsUnknown() throws Exception {
        server().on(Step.FINAL, LocalSmtpServer.andThen(connection -> connection.write("250 Ok"), stall()));

        NotificationSendResult result = assertBounded(() -> send(LONG_READ, DEADLINE));

        assertThat(result.outcome()).isEqualTo(DeliveryOutcome.UNKNOWN);
        assertThat(result.errorCode()).isEqualTo(SmtpFailureClassifier.DEADLINE_EXCEEDED);
    }

    @Test
    void aRejectionReadBeforeTheDeadlineIsNotReplacedByIt() throws Exception {
        server().on(Step.FINAL, reply("554 Access denied"));

        // Retiene el hilo tras leer el rechazo hasta que el plazo ha vencido.
        BiFunction<Session, URLName, SmtpTransaction> slowAfterRejection = (session, url) ->
                new SmtpTransaction(session, url) {
                    @Override
                    protected void finishData() throws IOException, MessagingException {
                        try {
                            super.finishData();
                        } finally {
                            sleep(DEADLINE.multipliedBy(2));
                        }
                    }
                };

        harness = harness(server, LONG_READ, DEADLINE, SesSmtpTestKit.breaker(), slowAfterRejection);
        NotificationSendResult result = harness.provider().send(email(), recipient(TO));

        assertThat(result.outcome()).isEqualTo(DeliveryOutcome.PROVIDER_ERROR);
        assertThat(result.errorCode()).isEqualTo("SES_SMTP_FINAL_REPLY_554");
    }

    @Test
    void aSessionRejectionReadBeforeTheDeadlineIsNotReplacedByIt() throws Exception {
        server().on(Step.AUTH, reply("535 Authentication Credentials Invalid"));

        BiFunction<Session, URLName, SmtpTransaction> slowAfterRejection = (session, url) ->
                new SmtpTransaction(session, url) {
                    @Override
                    protected boolean protocolConnect(String host, int port, String user, String password)
                            throws MessagingException {
                        try {
                            return super.protocolConnect(host, port, user, password);
                        } finally {
                            sleep(DEADLINE.multipliedBy(2));
                        }
                    }
                };

        harness = harness(server, LONG_READ, DEADLINE, SesSmtpTestKit.breaker(), slowAfterRejection);
        NotificationSendResult result = harness.provider().send(email(), recipient(TO));

        assertThat(result.outcome()).isEqualTo(DeliveryOutcome.PROVIDER_ERROR);
        assertThat(result.errorCode()).isEqualTo(SmtpFailureClassifier.AUTH_FAILED);
    }

    // ── Liberación de recursos ──────────────────────────────────────────

    @Test
    void aFailureBuildingTheTransactionReleasesTheDeadlineAndOpensNoConnection() throws Exception {
        server();
        CircuitBreaker breaker = SesSmtpTestKit.breaker();

        harness = harness(server, LONG_READ, Duration.ofMinutes(5), breaker, (session, url) -> {
            throw new IllegalStateException("synthetic setup failure with " + SesSmtpTestKit.PASSWORD);
        });

        NotificationSendResult result = harness.provider().send(email(), recipient(TO));

        assertThat(result.outcome()).isEqualTo(DeliveryOutcome.PROVIDER_ERROR);
        assertThat(result.errorCode()).isEqualTo("SES_SMTP_SETUP_FAILED");
        assertThat(result.errorMessage()).doesNotContain(SesSmtpTestKit.PASSWORD);
        assertThat(watchdogQueueSize(harness.sender())).isZero();
        assertThat(server.connections()).isZero();
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    @Test
    void anErrorDuringTheSendStillReleasesDeadlineSocketAndBreakerPermission() throws Exception {
        server().on(Step.RCPT, stall());
        CircuitBreaker breaker = SesSmtpTestKit.breaker(
                new SesSmtpProperties.CircuitBreakerSettings(5, 10, 50f, Duration.ofMillis(1), 1));
        breaker.transitionToOpenState();
        sleep(Duration.ofMillis(20));

        BiFunction<Session, URLName, SmtpTransaction> failing = (session, url) ->
                new SmtpTransaction(session, url) {
                    @Override
                    protected void rcptTo() {
                        throw new AssertionError("synthetic failure");
                    }
                };

        harness = harness(server, LONG_READ, Duration.ofMinutes(5), breaker, failing);

        assertThatThrownBy(() -> harness.provider().send(email(), recipient(TO))).isInstanceOf(AssertionError.class);

        assertThat(watchdogQueueSize(harness.sender())).isZero();
        assertThat(server.awaitClientDisconnect(WITHIN_BOUND)).isTrue();
        // El único permiso de sonda vuelve a estar disponible.
        assertThat(breaker.tryAcquirePermission()).isTrue();
    }

    @Test
    void anErrorWhileClosingStillReleasesTheDeadline() throws Exception {
        server();

        BiFunction<Session, URLName, SmtpTransaction> failingClose = (session, url) ->
                new SmtpTransaction(session, url) {
                    @Override
                    public synchronized void close() {
                        throw new AssertionError("synthetic failure");
                    }
                };

        harness = harness(server, LONG_READ, Duration.ofMinutes(5), SesSmtpTestKit.breaker(), failingClose);

        assertThatThrownBy(() -> harness.provider().send(email(), recipient(TO))).isInstanceOf(AssertionError.class);

        assertThat(watchdogQueueSize(harness.sender())).isZero();
        assertThat(server.awaitClientDisconnect(WITHIN_BOUND)).isTrue();
    }

    @Test
    void theDeadlineOfAFinishedAttemptNeverTouchesTheNextOne() throws Exception {
        // Cada intento dura más que lo que le quedaba de plazo al anterior.
        Duration deadline = Duration.ofMillis(600);

        server().on(Step.FINAL, delayedReply(Duration.ofMillis(250), LocalSmtpServer.ACCEPTED_REPLY));
        harness = harness(server, LONG_READ, deadline);

        for (int i = 0; i < 5; i++) {
            assertThat(harness.provider().send(email(), recipient(TO)).outcome()).isEqualTo(DeliveryOutcome.ACCEPTED);
        }

        assertThat(watchdogQueueSize(harness.sender())).isZero();
    }

    @Test
    void repeatedFailuresAndDeadlineAbortsLeaveNoThreadsOrPendingTimers() throws Exception {
        server().on(Step.RCPT, stall());
        harness = harness(server, LONG_READ, Duration.ofMillis(120), neverOpens(), SmtpTransaction::new);
        Set<String> before = liveThreads();

        for (int i = 0; i < 12; i++) {
            harness.provider().send(email(), recipient(TO));
        }

        Thread watchdog = SesSmtpTestKit.watchdogThread(harness.sender());

        assertThat(watchdogQueueSize(harness.sender())).isZero();
        assertThat(liveThreads()).as("hilos creados por envío")
                .containsAnyElementsOf(before)
                .allMatch(name -> before.contains(name)
                        || name.startsWith("ses-smtp-deadline")
                        || name.startsWith("local-smtp"));

        harness.close();
        SesSmtpTestKit.assertWatchdogStopped(harness.sender(), watchdog);
    }

    @Test
    void successfulSendsCancelTheirDeadline() throws Exception {
        server();
        harness = harness(server, LONG_READ, Duration.ofSeconds(30));

        for (int i = 0; i < 5; i++) {
            assertThat(harness.provider().send(email(), recipient(TO)).outcome()).isEqualTo(DeliveryOutcome.ACCEPTED);
        }

        assertThat(watchdogQueueSize(harness.sender())).isZero();
    }

    @Test
    void afterShutdownNoConnectionIsOpenedWithoutAWatchdog() throws Exception {
        server();
        harness = harness(server, LONG_READ, DEADLINE);
        harness.sender().destroy();

        NotificationSendResult result = harness.provider().send(email(), recipient(TO));

        assertThat(result.outcome()).isEqualTo(DeliveryOutcome.RETRYABLE);
        assertThat(server.connections()).isZero();
    }

    @Test
    void aSendInProgressDuringShutdownStaysUnderItsDeadline() throws Exception {
        server().on(Step.RCPT, stall());
        harness = harness(server, LONG_READ, DEADLINE);
        CompletableFuture<NotificationSendResult> sending = CompletableFuture.supplyAsync(
                () -> harness.provider().send(email(), recipient(TO)));

        while (server.commands().stream().noneMatch(command -> command.startsWith("RCPT"))) {
            sleep(Duration.ofMillis(10));
        }

        harness.sender().destroy();

        assertThat(sending.get(WITHIN_BOUND.toSeconds(), TimeUnit.SECONDS).outcome())
                .isEqualTo(DeliveryOutcome.UNKNOWN);
    }

    @Test
    void theGuardRefusesEveryConnectedVariantSoAngusCannotBypassTheDeadline() {
        SmtpDeadlineGuard guard = new SmtpDeadlineGuard();

        assertThatThrownBy(() -> guard.createSocket("localhost", 25)).isInstanceOf(SocketException.class);
        assertThatThrownBy(() -> guard.createSocket(InetAddress.getLoopbackAddress(), 25))
                .isInstanceOf(SocketException.class);
    }

    @Test
    void aSocketRequestedAfterTheDeadlineIsNotHandedOut() {
        SmtpDeadlineGuard guard = new SmtpDeadlineGuard();

        guard.expire();

        assertThat(guard.expired()).isTrue();
        assertThatThrownBy(guard::createSocket).isInstanceOf(SocketException.class);
    }

    @Test
    void aFailingGuardDoesNotFallBackToAnUnprotectedSocket() throws Exception {
        server();
        // Un plazo mínimo vence antes de crear el socket: la fábrica falla y no hay conexión.
        harness = harness(server, LONG_READ, Duration.ofMillis(1), neverOpens(), SmtpTransaction::new);

        for (int i = 0; i < 50; i++) {
            NotificationSendResult result = harness.provider().send(email(), recipient(TO));
            assertThat(result.outcome()).isNotEqualTo(DeliveryOutcome.ACCEPTED);
        }
    }

    // ── Planificador ────────────────────────────────────────────────────

    @Test
    void anUnrelatedTaskOnTheSameSchedulerProgressesOnceTheSendIsTerminated() throws Exception {
        server().on(Step.FINAL, stall());
        harness = harness(server, LONG_READ, DEADLINE);

        // Un único hilo, como el planificador por defecto de Spring.
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("single-scheduler-");
        scheduler.setDaemon(true);
        scheduler.initialize();

        try {
            CountDownLatch sendStarted = new CountDownLatch(1);
            CountDownLatch sendFinished = new CountDownLatch(1);
            CountDownLatch unrelatedRan = new CountDownLatch(1);

            scheduler.execute(() -> {
                sendStarted.countDown();
                harness.provider().send(email(), recipient(TO));
                sendFinished.countDown();
            });
            assertThat(sendStarted.await(5, TimeUnit.SECONDS)).isTrue();
            scheduler.execute(unrelatedRan::countDown);

            assertThat(unrelatedRan.await(DEADLINE.toMillis() / 3, TimeUnit.MILLISECONDS))
                    .as("el único hilo está ocupado mientras dura el envío").isFalse();
            assertThat(unrelatedRan.await(WITHIN_BOUND.toSeconds(), TimeUnit.SECONDS)).isTrue();
            assertThat(sendFinished.getCount()).isZero();
        } finally {
            scheduler.shutdown();
        }
    }

    // ── Soporte ─────────────────────────────────────────────────────────

    private static NotificationSendResult assertBounded(java.util.function.Supplier<NotificationSendResult> send) {
        long start = System.nanoTime();
        NotificationSendResult result = send.get();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(elapsed).isLessThan(WITHIN_BOUND);
        return result;
    }

    /** Estas pruebas repiten fallos a propósito: el circuito no debe interrumpirlas. */
    private static CircuitBreaker neverOpens() {
        return CircuitBreaker.of("ses-test", CircuitBreakerConfig.custom()
                .failureRateThreshold(100)
                .slidingWindowSize(1000)
                .minimumNumberOfCalls(1000)
                .build());
    }

    private static int watchdogQueueSize(SesSmtpSender sender) {
        return SesSmtpTestKit.watchdog(sender).getQueue().size();
    }

    private static Set<String> liveThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .map(Thread::getName)
                .collect(Collectors.toSet());
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
