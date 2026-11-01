package co.edu.unicauca.piedrazul.backend.notifications.logging;

import co.edu.unicauca.piedrazul.backend.notifications.domain.model.FailureType;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.StringJoiner;

public final class NotificationLogger {

    private static final Logger log = LoggerFactory.getLogger(NotificationLogger.class);

    private static final String CREATED = "NOTIFICATION_CREATED";
    private static final String SCHEDULED = "NOTIFICATION_SCHEDULED";
    private static final String DISPATCH_STARTED = "NOTIFICATION_DISPATCH_STARTED";
    private static final String ATTEMPT_CREATED = "NOTIFICATION_ATTEMPT_CREATED";
    private static final String DISPATCHED = "NOTIFICATION_DISPATCHED";
    private static final String ATTEMPT_FAILED = "NOTIFICATION_ATTEMPT_FAILED";
    private static final String RETRY_SCHEDULED = "NOTIFICATION_RETRY_SCHEDULED";
    private static final String FALLBACK = "NOTIFICATION_FALLBACK";
    private static final String DELIVERED = "NOTIFICATION_DELIVERED";
    private static final String WEBHOOK_RECEIVED = "NOTIFICATION_WEBHOOK_RECEIVED";
    private static final String STATUS_UPDATED = "NOTIFICATION_STATUS_UPDATED";
    private static final String STALE = "NOTIFICATION_STALE";
    private static final String CHANNEL_SKIPPED = "NOTIFICATION_CHANNEL_SKIPPED";
    private static final String FAILED = "NOTIFICATION_FAILED";
    private static final String ACCEPTANCE_UNKNOWN = "NOTIFICATION_ACCEPTANCE_UNKNOWN";
    private static final String EXPIRED = "NOTIFICATION_EXPIRED";
    private static final String OWNERSHIP_LOST = "NOTIFICATION_OWNERSHIP_LOST";
    private static final String INTERRUPTED = "NOTIFICATION_DISPATCH_INTERRUPTED";
    private static final String LATE_ACCEPTANCE = "NOTIFICATION_LATE_ACCEPTANCE";

    private NotificationLogger() {
    }

    public static void created(NotificationLogContext ctx) {
        log.info("{} {}", CREATED, base(ctx));
    }

    public static void scheduled(
            NotificationLogContext ctx,
            Instant scheduledAt
    ) {
        log.info(
                "{} {} scheduledAt={}",
                SCHEDULED,
                base(ctx),
                scheduledAt
        );
    }

    public static void dispatchStarted(NotificationLogContext ctx) {
        log.info("{} {}", DISPATCH_STARTED, base(ctx));
    }

    public static void attemptCreated(NotificationLogContext ctx) {
        log.info("{} {}", ATTEMPT_CREATED, base(ctx));
    }

    public static void dispatched(
            NotificationLogContext ctx,
            String providerMessageId
    ) {
        log.info(
                "{} {} providerMessageId={}",
                DISPATCHED,
                base(ctx),
                providerMessageId
        );
    }

    public static void attemptFailed(
            NotificationLogContext ctx,
            FailureType failureType,
            String reason
    ) {
        log.warn(
                "{} {} failureType={} reason={}",
                ATTEMPT_FAILED,
                base(ctx),
                failureType,
                reason
        );
    }

    public static void retryScheduled(
            NotificationLogContext ctx,
            Instant retryAt,
            String reason
    ) {
        log.warn(
                "{} {} retryAt={} reason={}",
                RETRY_SCHEDULED,
                base(ctx),
                retryAt,
                reason
        );
    }

    public static void fallback(
            NotificationLogContext ctx,
            NotificationChannel from,
            NotificationChannel to,
            String reason
    ) {
        log.warn(
                "{} {} fromChannel={} toChannel={} reason={}",
                FALLBACK,
                base(ctx),
                from,
                to,
                reason
        );
    }

    public static void delivered(NotificationLogContext ctx) {
        log.info("{} {}", DELIVERED, base(ctx));
    }

    public static void webhookReceived(
            NotificationLogContext ctx,
            String eventType,
            String rawStatus
    ) {
        log.info(
                "{} {} eventType={} rawStatus={}",
                WEBHOOK_RECEIVED,
                base(ctx),
                eventType,
                rawStatus
        );
    }

    public static void statusUpdated(
            NotificationLogContext ctx,
            String status
    ) {
        log.info(
                "{} {} status={}",
                STATUS_UPDATED,
                base(ctx),
                status
        );
    }

    public static void skipped(
            NotificationLogContext ctx,
            NotificationChannel channel,
            String reason
    ) {
        log.warn(
                "{} {} channel={} reason={}",
                CHANNEL_SKIPPED,
                base(ctx),
                channel,
                reason
        );
    }

    public static void stale(
            NotificationLogContext ctx,
            String reason
    ) {
        log.warn(
                "{} {} reason={}",
                STALE,
                base(ctx),
                reason
        );
    }

    /**
     * El proveedor pudo haber aceptado el mensaje: no se reintenta ni se cambia de canal. Requiere
     * revisión del operador; no incluye contenido ni mensajes de excepción.
     */
    public static void acceptanceUnknown(
            NotificationLogContext ctx,
            String providerErrorCode
    ) {
        log.error(
                "{} {} providerErrorCode={}",
                ACCEPTANCE_UNKNOWN,
                base(ctx),
                providerErrorCode
        );
    }

    public static void expired(
            NotificationLogContext ctx,
            Instant expiresAt
    ) {
        log.warn(
                "{} {} expiresAt={}",
                EXPIRED,
                base(ctx),
                expiresAt
        );
    }

    /** Otro trabajador avanzó el schedule: esta escritura no se aplicó. */
    public static void ownershipLost(
            NotificationLogContext ctx,
            String stage
    ) {
        log.warn(
                "{} {} stage={}",
                OWNERSHIP_LOST,
                base(ctx),
                stage
        );
    }

    /**
     * El proveedor aceptó el mensaje después de que otro actor cerrara el trabajo. No cambia el
     * estado: es la evidencia para el operador de que un intento UNKNOWN sí fue aceptado.
     */
    public static void lateAcceptance(
            NotificationLogContext ctx,
            String providerMessageId
    ) {
        log.error(
                "{} {} providerMessageId={}",
                LATE_ACCEPTANCE,
                base(ctx),
                providerMessageId
        );
    }

    /**
     * Error inesperado durante el despacho de un schedule. Solo se registra el tipo de la
     * excepción: sus mensajes pueden arrastrar valores de la fila (incluido el código).
     */
    public static void dispatchInterrupted(
            NotificationLogContext ctx,
            String stage,
            Throwable error
    ) {
        log.error(
                "{} {} stage={} errorType={}",
                INTERRUPTED,
                base(ctx),
                stage,
                error.getClass().getName()
        );
    }

    public static void failed(
            NotificationLogContext ctx,
            List<NotificationChannel> triedChannels,
            String lastError
    ) {
        log.error(
                "{} {} channelsTried={} lastError={}",
                FAILED,
                base(ctx),
                triedChannels,
                lastError
        );
    }

    private static String base(NotificationLogContext ctx) {
        StringJoiner joiner = new StringJoiner(" ");

        append(joiner, "notificationId", ctx.notificationId());
        append(joiner, "scheduleId", ctx.scheduleId());
        append(joiner, "attemptId", ctx.attemptId());
        append(joiner, "aggregateType", ctx.aggregateType());
        append(joiner, "aggregateId", ctx.aggregateId());
        append(joiner, "notificationType", ctx.type());
        append(joiner, "channel", ctx.channel());
        append(joiner, "providerName", ctx.providerName());
        append(joiner, "attemptNumber", ctx.attemptNumber());

        return joiner.toString();
    }

    private static void append(
            StringJoiner joiner,
            String key,
            Object value
    ) {
        if (value != null) {
            joiner.add(key + "=" + value);
        }
    }
}