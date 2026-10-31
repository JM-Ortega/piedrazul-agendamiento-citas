package co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.email.ses;

import javax.net.SocketFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;

/**
 * Fábrica de un único socket por envío que conserva el socket TCP real para poder cerrarlo desde
 * otro hilo. Con STARTTLS el {@code SSLSocket} va en capas sobre este socket, de modo que cerrarlo
 * aborta también una lectura, escritura o negociación TLS bloqueada; el cierre no pasa por el
 * transporte, cuyo monitor retiene el hilo bloqueado.
 *
 * <p>Si el plazo vence antes de crear el socket, el socket no llega a conectarse. Las demás
 * variantes de {@link SocketFactory} fallan: sin el socket real no hay plazo que hacer cumplir, y
 * Angus no debe caer a una conexión sin proteger.
 */
final class SmtpDeadlineGuard extends SocketFactory {

    private Socket socket;
    private boolean aborted;
    private volatile boolean expired;

    @Override
    public Socket createSocket() throws IOException {
        Socket created = new Socket();

        synchronized (this) {
            if (socket == null && !aborted) {
                socket = created;
                return created;
            }
        }

        created.close();
        throw new SocketException("SMTP send already aborted");
    }

    /** El plazo total venció: corta la conexión, si la hay. */
    void expire() {
        expired = true;
        abort();
    }

    /** Libera el socket; es idempotente y puede llamarse desde cualquier hilo. */
    void abort() {
        Socket toClose;

        synchronized (this) {
            aborted = true;
            toClose = socket;
        }

        if (toClose != null) {
            try {
                toClose.close();
            } catch (IOException exception) {
                // No queda nada que liberar: el descriptor ya está cerrado o en cierre.
            }
        }
    }

    boolean expired() {
        return expired;
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        throw unsupported();
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
        throw unsupported();
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        throw unsupported();
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
            throws IOException {
        throw unsupported();
    }

    private static SocketException unsupported() {
        return new SocketException("Only the unconnected variant keeps the SMTP deadline enforceable");
    }
}
