package co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.email.ses;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Servidor SMTP de pruebas en loopback, con STARTTLS real y guion por etapa. No retransmite nada:
 * solo conserva lo recibido. Todas sus esperas están acotadas por {@link #HARNESS_BOUND}, de modo
 * que ningún hilo sobrevive a la prueba aunque el cliente no se comporte como debe.
 */
final class LocalSmtpServer implements AutoCloseable {

    static final String ACCEPTED_REPLY = "250 Ok 0100018f0a1b2c3d-11112222-3333-4444-5555-666677778888-000000";
    static final String SES_MESSAGE_ID = "0100018f0a1b2c3d-11112222-3333-4444-5555-666677778888-000000";

    private static final Duration HARNESS_BOUND = Duration.ofSeconds(20);
    private static final Map<String, Tls> TLS_BY_HOST = new ConcurrentHashMap<>();

    enum Step { GREETING, EHLO, STARTTLS, AUTH, MAIL, RCPT, DATA, BODY, FINAL, QUIT }

    @FunctionalInterface
    interface Action {
        void run(Connection connection) throws Exception;
    }

    private final ServerSocket listener;
    private final Tls tls;
    private final Map<Step, Action> script = new EnumMap<>(Step.class);
    private final AtomicInteger connections = new AtomicInteger();
    private final List<Thread> threads = new CopyOnWriteArrayList<>();
    private final List<byte[]> messages = new CopyOnWriteArrayList<>();
    private final List<String> commands = new CopyOnWriteArrayList<>();
    private final CountDownLatch disconnected = new CountDownLatch(1);
    private final Thread acceptor;
    private volatile boolean offerAuth = true;

    LocalSmtpServer() throws IOException {
        this(Tls.forHost("localhost"));
    }

    LocalSmtpServer(Tls tls) throws IOException {
        this.tls = tls;
        this.listener = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        this.acceptor = Thread.ofPlatform().daemon().name("local-smtp-acceptor").start(this::acceptLoop);
    }

    LocalSmtpServer on(Step step, Action action) {
        script.put(step, action);
        return this;
    }

    LocalSmtpServer withoutAuth() {
        offerAuth = false;
        return this;
    }

    int port() {
        return listener.getLocalPort();
    }

    int connections() {
        return connections.get();
    }

    List<byte[]> messages() {
        return messages;
    }

    List<String> commands() {
        return commands;
    }

    /** Espera a que el cliente haya cerrado la conexión de una etapa detenida. */
    boolean awaitClientDisconnect(Duration timeout) throws InterruptedException {
        return disconnected.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    // ── Guiones ─────────────────────────────────────────────────────────

    static Action reply(String line) {
        return connection -> connection.write(line + "\r\n");
    }

    static Action closeNow() {
        return Connection::close;
    }

    /** Cierre abortivo: el cliente recibe un RST en lugar de un fin de flujo. */
    static Action resetNow() {
        return Connection::reset;
    }

    /** No responde; se limita a esperar a que el cliente corte. */
    static Action stall() {
        return Connection::awaitClose;
    }

    /** Responde tarde, sin haber dejado de escuchar. */
    static Action delayedReply(Duration delay, String line) {
        return connection -> {
            Thread.sleep(delay);
            connection.write(line + "\r\n");
        };
    }

    /** Un byte cada poco: cada lectura progresa y ningún tiempo por operación llega a vencer. */
    static Action dripForever(Duration interval) {
        return connection -> {
            long end = System.nanoTime() + HARNESS_BOUND.toNanos();

            connection.write("250-");

            while (System.nanoTime() < end) {
                connection.write("x");
                Thread.sleep(interval);
            }
        };
    }

    static Action andThen(Action first, Action second) {
        return connection -> {
            first.run(connection);
            second.run(connection);
        };
    }

    // ── Protocolo ───────────────────────────────────────────────────────

    private void acceptLoop() {
        while (!listener.isClosed()) {
            try {
                Socket socket = listener.accept();
                connections.incrementAndGet();
                threads.add(Thread.ofPlatform().daemon().name("local-smtp-connection")
                        .start(() -> serve(socket)));
            } catch (IOException exception) {
                return;
            }
        }
    }

    private void serve(Socket socket) {
        try (Connection connection = new Connection(socket, tls)) {
            run(connection);
        } catch (Exception exception) {
            // El cliente cortó o el guion cerró: es parte de lo que se prueba.
        } finally {
            disconnected.countDown();
        }
    }

    private void run(Connection connection) throws Exception {
        act(Step.GREETING, connection, reply("220 local ESMTP"));

        while (!connection.closed) {
            String line = connection.readLine();

            if (line == null) {
                return;
            }

            String verb = line.split("[ :]", 2)[0].toUpperCase();
            commands.add(verb.equals("AUTH") ? "AUTH" : line);

            switch (verb) {
                case "EHLO" -> act(Step.EHLO, connection, ehlo(connection));
                case "HELO" -> connection.write("250 local\r\n");
                case "STARTTLS" -> act(Step.STARTTLS, connection, conn -> {
                    conn.write("220 ready\r\n");
                    conn.upgrade();
                });
                case "AUTH" -> act(Step.AUTH, connection, reply("235 authenticated"));
                case "MAIL" -> act(Step.MAIL, connection, reply("250 Ok"));
                case "RCPT" -> act(Step.RCPT, connection, reply("250 Ok"));
                case "RSET" -> connection.write("250 Ok\r\n");
                case "DATA" -> {
                    act(Step.DATA, connection, reply("354 go"));

                    if (!connection.closed && !script.containsKey(Step.DATA)) {
                        act(Step.BODY, connection, conn -> {
                            messages.add(conn.readBody());
                            act(Step.FINAL, conn, reply(ACCEPTED_REPLY));
                        });
                    }
                }
                case "QUIT" -> {
                    act(Step.QUIT, connection, reply("221 bye"));
                    return;
                }
                default -> connection.write("500 unknown\r\n");
            }
        }
    }

    private Action ehlo(Connection connection) {
        if (!connection.secured) {
            return reply("250-local\r\n250 STARTTLS");
        }

        return reply(offerAuth ? "250-local\r\n250 AUTH PLAIN LOGIN" : "250 local");
    }

    private void act(Step step, Connection connection, Action fallback) throws Exception {
        script.getOrDefault(step, fallback).run(connection);
    }

    @Override
    public void close() throws Exception {
        listener.close();
        acceptor.join(HARNESS_BOUND);

        for (Thread thread : threads) {
            thread.interrupt();
            thread.join(HARNESS_BOUND);
        }
    }

    /** Conexión del lado del servidor. */
    static final class Connection implements AutoCloseable {
        private final Socket raw;
        private final Tls tls;
        private Socket socket;
        private InputStream in;
        private OutputStream out;
        private volatile boolean closed;
        private boolean secured;

        private Connection(Socket raw, Tls tls) throws IOException {
            this.raw = raw;
            this.tls = tls;
            this.socket = raw;
            raw.setSoTimeout((int) HARNESS_BOUND.toMillis());
            this.in = raw.getInputStream();
            this.out = raw.getOutputStream();
        }

        void write(String text) throws IOException {
            out.write(text.getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }

        @Override
        public void close() {
            closed = true;

            try {
                socket.close();
            } catch (IOException exception) {
                // Cierre de una conexión de prueba.
            }
        }

        private void reset() throws IOException {
            closed = true;
            raw.setSoLinger(true, 0);
            raw.close();
        }

        private void awaitClose() throws IOException {
            try {
                while (in.read() != -1) {
                    // Lo que llegue se descarta: el guion no responde.
                }
            } catch (SocketTimeoutException exception) {
                throw new IOException("El cliente no cortó dentro del límite de la prueba", exception);
            }

            closed = true;
        }

        private void upgrade() throws IOException {
            SSLSocket upgraded = (SSLSocket) tls.server().createSocket(raw, "localhost", raw.getPort(), true);
            upgraded.setUseClientMode(false);
            upgraded.startHandshake();
            socket = upgraded;
            in = upgraded.getInputStream();
            out = upgraded.getOutputStream();
            secured = true;
        }

        String readLine() throws IOException {
            ByteArrayOutputStream line = new ByteArrayOutputStream();

            for (int current = in.read(); current != -1; current = in.read()) {
                if (current == '\n') {
                    return line.toString(StandardCharsets.UTF_8).stripTrailing();
                }

                line.write(current);
            }

            return null;
        }

        private byte[] readBody() throws IOException {
            ByteArrayOutputStream body = new ByteArrayOutputStream();

            for (String line = readLine(); line != null && !line.equals("."); line = readLine()) {
                body.write(line.getBytes(StandardCharsets.UTF_8));
                body.write("\r\n".getBytes(StandardCharsets.US_ASCII));
            }

            return body.toByteArray();
        }
    }

    /** Certificado sintético autofirmado, generado con keytool para un nombre de host. */
    record Tls(SSLSocketFactory server, SSLSocketFactory client) {

        static Tls forHost(String host) {
            return TLS_BY_HOST.computeIfAbsent(host, Tls::generate);
        }

        private static Tls generate(String host) {
            try {
                Path directory = Files.createTempDirectory("local-smtp-tls");
                Path keystore = directory.resolve("server.p12");
                char[] password = "synthetic".toCharArray();

                Process keytool = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                        "-genkeypair", "-alias", "smtp", "-keyalg", "EC", "-groupname", "secp256r1",
                        "-sigalg", "SHA256withECDSA", "-validity", "2",
                        "-dname", "CN=" + host,
                        "-ext", "SAN=dns:" + host,
                        "-storetype", "PKCS12",
                        "-keystore", keystore.toString(),
                        "-storepass", new String(password))
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .start();

                if (!keytool.waitFor(HARNESS_BOUND.toSeconds(), TimeUnit.SECONDS) || keytool.exitValue() != 0) {
                    keytool.destroyForcibly();
                    throw new IllegalStateException("keytool no generó el certificado de prueba");
                }

                KeyStore store = KeyStore.getInstance("PKCS12");

                try (InputStream input = Files.newInputStream(keystore)) {
                    store.load(input, password);
                }

                Files.delete(keystore);
                Files.delete(directory);

                KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                keys.init(store, password);
                TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                trust.init(store);

                SSLContext serverContext = SSLContext.getInstance("TLS");
                serverContext.init(keys.getKeyManagers(), null, null);
                SSLContext clientContext = SSLContext.getInstance("TLS");
                clientContext.init(null, trust.getTrustManagers(), null);

                return new Tls(serverContext.getSocketFactory(), clientContext.getSocketFactory());
            } catch (Exception exception) {
                throw new IllegalStateException("No se pudo preparar el TLS de prueba", exception);
            }
        }
    }
}
