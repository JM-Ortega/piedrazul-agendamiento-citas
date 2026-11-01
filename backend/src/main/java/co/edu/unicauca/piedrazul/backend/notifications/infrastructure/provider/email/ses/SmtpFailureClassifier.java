package co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.email.ses;

import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.DeliveryOutcome;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.email.ses.SmtpOutcome.Health;
import jakarta.mail.AuthenticationFailedException;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLPeerUnverifiedException;
import java.io.EOFException;
import java.io.IOException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.security.cert.CertificateException;
import java.util.Set;

/**
 * Traduce un envío que no terminó en aceptación confirmada a un resultado. Decide con la etapa y
 * la respuesta registradas por {@link SmtpTransaction}, sin inferir nada de los indicadores de
 * Angus ({@code MESSAGE_NOT_DELIVERED}, {@code validUnsent}), que se activan también cuando la
 * respuesta final se pierde.
 *
 * <ul>
 *   <li>Antes de la transacción ningún mensaje salió: el fallo prueba la no aceptación.</li>
 *   <li>Dentro de ella, solo un rechazo 4xx o 5xx completo y bien formado la prueba. Cualquier otro
 *       final (tiempo agotado, conexión cerrada, respuesta cortada, malformada o inesperada) es aceptación
 *       desconocida, también antes del cuerpo: se sacrifica ese reintento a cambio de no deducir
 *       nada de un fallo de transporte.</li>
 * </ul>
 */
final class SmtpFailureClassifier {

    static final String DEADLINE_EXCEEDED = "SES_SMTP_DEADLINE_EXCEEDED";
    static final String UNCONFIRMED = "SES_SMTP_UNCONFIRMED";
    static final String AUTH_FAILED = "SES_SMTP_AUTH_FAILED";

    // 421: el servicio cierra el canal, sea cual sea la etapa.
    private static final int SERVICE_UNAVAILABLE = 421;
    private static final int MESSAGE_TOO_LONG = 552;
    private static final int AMBIGUOUS_PERMANENT = 554;
    // Rechazos de autenticación: no dependen del destinatario ni del mensaje.
    private static final Set<Integer> AUTHENTICATION_CODES = Set.of(530, 534, 535, 538);

    private SmtpFailureClassifier() {
    }

    static SmtpOutcome classify(SmtpTransaction transaction, Throwable failure, boolean deadlineExpired) {
        SmtpStage stage = transaction.stage();
        SmtpReply reply = transaction.reply();

        if (stage.precedesTransaction()) {
            return classifySession(stage, reply, failure, deadlineExpired);
        }

        if (reply == null || !reply.isRejection()) {
            return SmtpOutcome.of(DeliveryOutcome.UNKNOWN, Health.FAULT,
                    deadlineExpired ? DEADLINE_EXCEEDED : UNCONFIRMED);
        }

        return classifyRejection(stage, reply);
    }

    private static SmtpOutcome classifySession(
            SmtpStage stage,
            SmtpReply reply,
            Throwable failure,
            boolean deadlineExpired
    ) {
        // Una respuesta decisiva ya leída prevalece sobre lo que venza o falle después.
        if (reply != null && reply.isTransientRejection()) {
            return SmtpOutcome.of(DeliveryOutcome.RETRYABLE, Health.FAULT, "SES_SMTP_SESSION_" + reply.code());
        }

        if (reply != null && reply.isPermanentRejection()) {
            return SmtpOutcome.of(DeliveryOutcome.PROVIDER_ERROR, Health.FAULT,
                    failure instanceof AuthenticationFailedException
                            ? AUTH_FAILED
                            : "SES_SMTP_SESSION_" + reply.code());
        }

        if (deadlineExpired) {
            return SmtpOutcome.of(DeliveryOutcome.RETRYABLE, Health.FAULT, DEADLINE_EXCEEDED);
        }

        if (hasCause(failure, CertificateException.class) || hasCause(failure, SSLPeerUnverifiedException.class)) {
            return SmtpOutcome.of(DeliveryOutcome.PROVIDER_ERROR, Health.FAULT, "SES_SMTP_TLS_CERTIFICATE");
        }

        // Antes de la transacción una conexión perdida es transitoria, también dentro de TLS.
        // Angus devuelve -1 si la conexión termina sin respuesta o esta es ilegible.
        if (isConnectionLost(failure) || (reply != null && reply.code() == -1)) {
            return SmtpOutcome.of(DeliveryOutcome.RETRYABLE, Health.FAULT,
                    stage == SmtpStage.TLS ? "SES_SMTP_TLS_INTERRUPTED" : "SES_SMTP_CONNECT_FAILED");
        }

        // Negociación rechazada sin fallo de red: protocolos, cifrados o un par que no habla TLS.
        if (hasCause(failure, SSLException.class)) {
            return SmtpOutcome.of(DeliveryOutcome.PROVIDER_ERROR, Health.FAULT, "SES_SMTP_TLS_POLICY");
        }

        if (hasCause(failure, IOException.class)) {
            return SmtpOutcome.of(DeliveryOutcome.RETRYABLE, Health.FAULT, "SES_SMTP_CONNECT_FAILED");
        }

        if (failure instanceof AuthenticationFailedException) {
            return SmtpOutcome.of(DeliveryOutcome.PROVIDER_ERROR, Health.FAULT, AUTH_FAILED);
        }

        // Sesión establecida sin lo exigido: sin STARTTLS o sin AUTH.
        return SmtpOutcome.of(DeliveryOutcome.PROVIDER_ERROR, Health.FAULT, "SES_SMTP_SESSION_REFUSED");
    }

    private static boolean isConnectionLost(Throwable failure) {
        return hasCause(failure, EOFException.class)
                || hasCause(failure, SocketException.class)
                || hasCause(failure, SocketTimeoutException.class);
    }

    private static SmtpOutcome classifyRejection(SmtpStage stage, SmtpReply reply) {
        String code = "SES_SMTP_" + stage + "_" + reply.code();

        if (AUTHENTICATION_CODES.contains(reply.code())) {
            return SmtpOutcome.of(DeliveryOutcome.PROVIDER_ERROR, Health.FAULT, code);
        }

        if (reply.isTransientRejection()) {
            // Un 4xx a RCPT puede ser propio del destinatario: solo el 421 acusa al servicio.
            Health health = stage == SmtpStage.RCPT && reply.code() != SERVICE_UNAVAILABLE
                    ? Health.NEUTRAL
                    : Health.FAULT;

            return SmtpOutcome.of(DeliveryOutcome.RETRYABLE, health, code);
        }

        return isRecipientOrMessageRejection(stage, reply.code())
                ? SmtpOutcome.of(DeliveryOutcome.REJECTED, Health.NEUTRAL, code)
                : SmtpOutcome.of(DeliveryOutcome.PROVIDER_ERROR, Health.FAULT, code);
    }

    /**
     * Solo es rechazo del destinatario o del mensaje lo que la etapa y el código prueban. SES responde
     * {@code 554} a la secuencia final tanto por falta de autorización como por identidades sin
     * verificar y documenta el 552 como único rechazo propio del mensaje; su texto no se interpreta.
     * Un 5xx sin atribuir cuenta contra el proveedor: nunca se reintenta, pero queda visible para
     * el circuit breaker. El remitente y la sesión se resuelven antes de conocer el contenido.
     */
    private static boolean isRecipientOrMessageRejection(SmtpStage stage, int code) {
        return switch (stage) {
            case RCPT -> code != AMBIGUOUS_PERMANENT;
            case FINAL_REPLY -> code == MESSAGE_TOO_LONG;
            default -> false;
        };
    }

    private static boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
        Throwable current = failure;

        // Angus encadena con getNextException, que MessagingException expone como causa.
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (type.isInstance(current)) {
                return true;
            }

            current = current.getCause();
        }

        return false;
    }
}
