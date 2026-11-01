package co.edu.unicauca.piedrazul.backend.notifications.domain.policy;

import co.edu.unicauca.piedrazul.backend.notifications.domain.model.AttemptStatus;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.Notification;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationAttempt;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationSchedule;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationStatus;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.ScheduleStatus;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.DispatchFinish;

import java.time.Instant;
import java.util.List;

/**
 * Resuelve un trabajo que dejó de avanzar. Decide sobre el historial completo de intentos de la
 * notificación, leído bajo el bloqueo del schedule. La regla que lo sostiene viene del despacho:
 * ningún proveedor se invoca sin un intento PROCESSING confirmado antes. Por eso:
 *
 * <ul>
 *   <li>con un intento en curso no se puede descartar la invocación (haya llegado o no al
 *       proveedor): se cierra como aceptación desconocida y nunca se reenvía;</li>
 *   <li>sin intentos en curso ni aceptados, nada quedó entregado: el trabajo puede reencolarse, y
 *       es el despachador quien revalida canales, reintentos y vigencia al retomarlo.</li>
 * </ul>
 *
 * Reencolar no consume reintentos del canal porque nada se envió. Lo acota
 * {@link #MAX_REQUEUES}, que evita que un trabajo que tumba al proceso se repita sin fin.
 */
public final class InterruptedDispatchPolicy {

    public static final String ACCEPTANCE_UNKNOWN = "ACCEPTANCE_UNKNOWN";
    public static final String DISPATCH_INTERRUPTED = "DISPATCH_INTERRUPTED";
    public static final String INTERRUPTED_MESSAGE =
            "Intento en curso interrumpido; el proveedor pudo haberlo aceptado";

    /** Reencolados por notificación; agotados, el trabajo termina como FAILED. */
    public static final int MAX_REQUEUES = 3;

    public enum Resolution {
        /** Sigue vivo, ya se resolvió o se reclamó de nuevo: no se toca nada. */
        NONE,
        /** El schedule ya no estaba reclamado: solo se cierra el intento huérfano. */
        ATTEMPT_CLOSED,
        /** La notificación ya había terminado: el schedule se alinea con ella. */
        SETTLED,
        ACCEPTED,
        ACCEPTANCE_UNKNOWN,
        EXPIRED,
        REQUEUED,
        REQUEUES_EXHAUSTED
    }

    public record Decision(Resolution resolution, List<NotificationAttempt> closedAttempts) {
    }

    private InterruptedDispatchPolicy() {
    }

    /**
     * Aplica la resolución sobre los objetos recibidos. El llamador los lee bajo el bloqueo del
     * schedule y los persiste en esa misma transacción, salvo que resulte {@link Resolution#NONE}.
     *
     * @param staleBefore un intento en curso o un reclamo es antiguo si es anterior a este instante
     */
    public static Decision resolve(
            NotificationSchedule schedule,
            Notification notification,
            List<NotificationAttempt> attempts,
            Instant staleBefore,
            Instant now
    ) {
        List<NotificationAttempt> inFlight = attempts.stream()
                .filter(attempt -> attempt.getStatus() == AttemptStatus.PROCESSING)
                .toList();

        boolean claimed = schedule.getStatus() == ScheduleStatus.PROCESSING;

        if (inFlight.stream().anyMatch(attempt -> !attempt.getUpdatedAt().isBefore(staleBefore))
                || (inFlight.isEmpty() && !(claimed && isOldClaim(schedule, staleBefore)))) {
            return new Decision(Resolution.NONE, List.of());
        }

        inFlight.forEach(attempt ->
                attempt.markAcceptanceUnknown(ACCEPTANCE_UNKNOWN, INTERRUPTED_MESSAGE, now));

        Resolution resolution = claimed
                ? settle(schedule, notification, attempts, now)
                : closeOrphanAttempts(notification);

        return new Decision(resolution, inFlight);
    }

    private static boolean isOldClaim(NotificationSchedule schedule, Instant staleBefore) {
        return schedule.getProcessingStartedAt() != null
                && schedule.getProcessingStartedAt().isBefore(staleBefore);
    }

    private static Resolution closeOrphanAttempts(Notification notification) {
        if (isSettled(notification)) {
            notification.redactSensitiveVariables();
        }

        return Resolution.ATTEMPT_CLOSED;
    }

    private static Resolution settle(
            NotificationSchedule schedule,
            Notification notification,
            List<NotificationAttempt> attempts,
            Instant now
    ) {
        if (isSettled(notification)) {
            schedule.settleWith(notification.getStatus(), now);
            notification.redactSensitiveVariables();
            return Resolution.SETTLED;
        }

        if (notification.getStatus() == NotificationStatus.PENDING) {
            notification.markProcessing(now);
        }

        if (attempts.stream().anyMatch(InterruptedDispatchPolicy::isAccepted)) {
            DispatchFinish.ACCEPTED.applyTo(notification, schedule, null, now);
            return Resolution.ACCEPTED;
        }

        if (attempts.stream().anyMatch(attempt -> attempt.getStatus() == AttemptStatus.UNKNOWN)) {
            DispatchFinish.FAILED.applyTo(notification, schedule, null, now);
            return Resolution.ACCEPTANCE_UNKNOWN;
        }

        if (notification.isExpiredAt(now)) {
            DispatchFinish.EXPIRED.applyTo(notification, schedule, null, now);
            return Resolution.EXPIRED;
        }

        if (notification.dispatchRecoveries() >= MAX_REQUEUES) {
            DispatchFinish.FAILED.applyTo(notification, schedule, null, now);
            return Resolution.REQUEUES_EXHAUSTED;
        }

        notification.recordDispatchRecovery(now);
        DispatchFinish.RETRY.applyTo(notification, schedule, now, now);
        return Resolution.REQUEUED;
    }

    private static boolean isSettled(Notification notification) {
        return notification.getStatus() != NotificationStatus.PENDING
                && notification.getStatus() != NotificationStatus.PROCESSING;
    }

    /** Evidencia de aceptación: excluye lo que solo no se puede descartar (UNKNOWN y en curso). */
    private static boolean isAccepted(NotificationAttempt attempt) {
        return attempt.mayHaveBeenAccepted()
                && attempt.getStatus() != AttemptStatus.PROCESSING
                && attempt.getStatus() != AttemptStatus.UNKNOWN;
    }
}
