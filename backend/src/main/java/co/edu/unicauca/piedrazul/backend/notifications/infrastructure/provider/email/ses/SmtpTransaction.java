package co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.email.ses;

import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.email.ses.ReplyFramingSslSocketFactory.ReplyFraming;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.URLName;
import org.eclipse.angus.mail.smtp.SMTPTransport;

import java.io.IOException;
import java.io.OutputStream;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Transporte SMTP de un solo envío que registra la etapa del protocolo y la respuesta decisiva de
 * esa etapa. El resultado se clasifica con estos datos y no con las excepciones de Angus, que no
 * distinguen un rechazo de un fallo de lectura: al final de los datos, una conexión cerrada
 * también llega como {@code SMTPSendFailedException}, y un error del {@code RSET} posterior a un
 * rechazo lo oculta. Solo el hilo del envío toca este estado.
 */
class SmtpTransaction extends SMTPTransport {

    /**
     * Angus nombra sus loggers con el paquete de la subclase, y en FINE o FINEST escribe respuestas
     * del servidor, direcciones y el mensaje completo. Se fija aquí para que un nivel global
     * detallado no los active; la referencia evita que se recolecte el logger y se pierda el nivel.
     */
    private static final Logger PROTOCOL_LOGGER =
            Logger.getLogger(SmtpTransaction.class.getPackageName());

    static {
        PROTOCOL_LOGGER.setLevel(Level.WARNING);
    }

    private SmtpStage stage = SmtpStage.SESSION;
    private SmtpReply reply;
    private boolean accepted;
    private String providerMessageId;
    // Sin datos observados nada cuenta como terminado: falla hacia la aceptación desconocida.
    private ReplyFraming framing = new ReplyFraming();

    SmtpTransaction(Session session, URLName url) {
        super(session, url);
    }

    void observeFraming(ReplyFraming framing) {
        this.framing = framing;
    }

    SmtpStage stage() {
        return stage;
    }

    /** Respuesta decisiva de la etapa: el primer rechazo de la transacción o la última leída. */
    SmtpReply reply() {
        return reply;
    }

    boolean accepted() {
        return accepted;
    }

    String providerMessageId() {
        return providerMessageId;
    }

    @Override
    protected void startTLS() throws MessagingException {
        enter(SmtpStage.TLS);
        super.startTLS();
        enter(SmtpStage.SESSION);
    }

    @Override
    protected void mailFrom() throws MessagingException {
        enter(SmtpStage.MAIL);
        super.mailFrom();
    }

    @Override
    protected void rcptTo() throws MessagingException {
        enter(SmtpStage.RCPT);
        super.rcptTo();
    }

    @Override
    protected OutputStream data() throws MessagingException {
        enter(SmtpStage.DATA);
        OutputStream body = super.data();

        // Sin un 354 exacto el mensaje no debe salir: de lo contrario, sus líneas llegarían al
        // servidor como comandos.
        if (!reply.isExactly(354)) {
            throw new MessagingException("Unexpected reply to DATA");
        }

        enter(SmtpStage.BODY);
        return body;
    }

    @Override
    protected void finishData() throws IOException, MessagingException {
        enter(SmtpStage.FINAL_REPLY);
        super.finishData();

        // Única prueba de aceptación: un 250 completo y bien formado a la secuencia final de datos.
        if (reply.isExactly(250)) {
            accepted = true;
            providerMessageId = reply.sesMessageId().orElse(null);
        }
    }

    @Override
    protected int readServerResponse() throws MessagingException {
        int code = super.readServerResponse();

        // Dentro de la transacción manda el primer rechazo: Angus emite después un RSET. La sesión
        // conserva la última respuesta: un EHLO rechazado se resuelve con HELO.
        if (stage.precedesTransaction()) {
            reply = SmtpReply.parse(code, getLastServerResponse(), true);
        } else if (reply == null || !reply.isRejection()) {
            reply = SmtpReply.parse(code, getLastServerResponse(), framing.endsWithCrlf());
        }

        return code;
    }

    private void enter(SmtpStage next) {
        stage = next;
        reply = null;
    }
}
