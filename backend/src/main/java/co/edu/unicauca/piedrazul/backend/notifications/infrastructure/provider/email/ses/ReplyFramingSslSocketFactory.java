package co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.email.ses;

import javax.net.ssl.HandshakeCompletedListener;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;

/**
 * Deja ver si una respuesta SMTP llegó completa. Angus descarta ese dato: {@code LineInputStream}
 * devuelve la línea parcial al llegar el fin de flujo, sin distinguirla de una terminada, y
 * {@code SMTPTransport} no da acceso a su flujo. Por eso se observan los bytes ya descifrados en la
 * única costura disponible: el {@code SSLSocket} que Angus pide a su fábrica para STARTTLS.
 *
 * <p>El socket envuelto delega todo en el socket TLS real, así que protocolos, confianza, nombre del
 * servidor y negociación siguen en manos de Angus y de la JVM. Solo se cubre STARTTLS: la fábrica
 * rechaza las variantes que abren conexión, igual que {@link SmtpDeadlineGuard}.
 */
final class ReplyFramingSslSocketFactory extends SSLSocketFactory {

    private final SSLSocketFactory delegate;
    private final ReplyFraming framing;

    ReplyFramingSslSocketFactory(SSLSocketFactory delegate, ReplyFraming framing) {
        this.delegate = delegate;
        this.framing = framing;
    }

    @Override
    public Socket createSocket(Socket socket, String host, int port, boolean autoClose) throws IOException {
        return new FramedSslSocket((SSLSocket) delegate.createSocket(socket, host, port, autoClose), framing);
    }

    @Override
    public String[] getDefaultCipherSuites() {
        return delegate.getDefaultCipherSuites();
    }

    @Override
    public String[] getSupportedCipherSuites() {
        return delegate.getSupportedCipherSuites();
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
        return new SocketException("Only STARTTLS over the guarded socket is supported");
    }

    /** Últimos dos bytes descifrados recibidos. Solo los toca el hilo del envío. */
    static final class ReplyFraming {
        private int previous = -1;
        private int last = -1;

        boolean endsWithCrlf() {
            return previous == '\r' && last == '\n';
        }

        private void record(int value) {
            previous = last;
            last = value;
        }

        private void record(byte[] bytes, int offset, int length) {
            for (int index = Math.max(offset, offset + length - 2); index < offset + length; index++) {
                record(bytes[index] & 0xFF);
            }
        }
    }

    private static final class FramedInputStream extends FilterInputStream {
        private final ReplyFraming framing;

        private FramedInputStream(InputStream in, ReplyFraming framing) {
            super(in);
            this.framing = framing;
        }

        @Override
        public int read() throws IOException {
            int value = in.read();

            if (value != -1) {
                framing.record(value);
            }

            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int count = in.read(bytes, offset, length);

            if (count > 0) {
                framing.record(bytes, offset, count);
            }

            return count;
        }
    }

    private static final class FramedSslSocket extends SSLSocket {
        private final SSLSocket socket;
        private final ReplyFraming framing;

        private FramedSslSocket(SSLSocket socket, ReplyFraming framing) {
            this.socket = socket;
            this.framing = framing;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return new FramedInputStream(socket.getInputStream(), framing);
        }

        @Override
        public OutputStream getOutputStream() throws IOException {
            return socket.getOutputStream();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }

        @Override
        public boolean isClosed() {
            return socket.isClosed();
        }

        @Override
        public boolean isConnected() {
            return socket.isConnected();
        }

        @Override
        public boolean isBound() {
            return socket.isBound();
        }

        @Override
        public int getPort() {
            return socket.getPort();
        }

        @Override
        public int getLocalPort() {
            return socket.getLocalPort();
        }

        @Override
        public InetAddress getInetAddress() {
            return socket.getInetAddress();
        }

        @Override
        public InetAddress getLocalAddress() {
            return socket.getLocalAddress();
        }

        @Override
        public SocketAddress getRemoteSocketAddress() {
            return socket.getRemoteSocketAddress();
        }

        @Override
        public int getSoTimeout() throws SocketException {
            return socket.getSoTimeout();
        }

        @Override
        public void setSoTimeout(int timeout) throws SocketException {
            socket.setSoTimeout(timeout);
        }

        @Override
        public SSLParameters getSSLParameters() {
            return socket.getSSLParameters();
        }

        @Override
        public void setSSLParameters(SSLParameters parameters) {
            socket.setSSLParameters(parameters);
        }

        @Override
        public SSLSession getHandshakeSession() {
            return socket.getHandshakeSession();
        }

        @Override
        public String getApplicationProtocol() {
            return socket.getApplicationProtocol();
        }

        @Override
        public String getHandshakeApplicationProtocol() {
            return socket.getHandshakeApplicationProtocol();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return socket.getSupportedCipherSuites();
        }

        @Override
        public String[] getEnabledCipherSuites() {
            return socket.getEnabledCipherSuites();
        }

        @Override
        public void setEnabledCipherSuites(String[] suites) {
            socket.setEnabledCipherSuites(suites);
        }

        @Override
        public String[] getSupportedProtocols() {
            return socket.getSupportedProtocols();
        }

        @Override
        public String[] getEnabledProtocols() {
            return socket.getEnabledProtocols();
        }

        @Override
        public void setEnabledProtocols(String[] protocols) {
            socket.setEnabledProtocols(protocols);
        }

        @Override
        public SSLSession getSession() {
            return socket.getSession();
        }

        @Override
        public void addHandshakeCompletedListener(HandshakeCompletedListener listener) {
            socket.addHandshakeCompletedListener(listener);
        }

        @Override
        public void removeHandshakeCompletedListener(HandshakeCompletedListener listener) {
            socket.removeHandshakeCompletedListener(listener);
        }

        @Override
        public void startHandshake() throws IOException {
            socket.startHandshake();
        }

        @Override
        public void setUseClientMode(boolean mode) {
            socket.setUseClientMode(mode);
        }

        @Override
        public boolean getUseClientMode() {
            return socket.getUseClientMode();
        }

        @Override
        public void setNeedClientAuth(boolean need) {
            socket.setNeedClientAuth(need);
        }

        @Override
        public boolean getNeedClientAuth() {
            return socket.getNeedClientAuth();
        }

        @Override
        public void setWantClientAuth(boolean want) {
            socket.setWantClientAuth(want);
        }

        @Override
        public boolean getWantClientAuth() {
            return socket.getWantClientAuth();
        }

        @Override
        public void setEnableSessionCreation(boolean flag) {
            socket.setEnableSessionCreation(flag);
        }

        @Override
        public boolean getEnableSessionCreation() {
            return socket.getEnableSessionCreation();
        }
    }
}
