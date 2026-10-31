package co.edu.unicauca.piedrazul.backend.notifications.domain.port.output;

import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationAttempt;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Una escritura atómica del despacho: como mucho un intento (nuevo o resuelto) más la transición
 * de notificación y schedule. Solo se aplica si el schedule sigue reclamado (PROCESSING) y el
 * número de intentos de la notificación coincide con {@code expectedAttemptCount}.
 *
 * @param newAttempt      intento que se inserta (en curso o ya fallido antes de enviar)
 * @param resolvedAttempt intento en curso (PROCESSING) que se actualiza con su resultado
 */
public record DispatchWrite(
        UUID scheduleId,
        int expectedAttemptCount,
        NotificationAttempt newAttempt,
        NotificationAttempt resolvedAttempt,
        DispatchFinish finish,
        Instant retryAt,
        Instant now
) {

    public DispatchWrite {
        Objects.requireNonNull(scheduleId, "El schedule es obligatorio");
        Objects.requireNonNull(finish, "La transición es obligatoria");
        Objects.requireNonNull(now, "La fecha actual es obligatoria");

        if (newAttempt != null && resolvedAttempt != null) {
            throw new IllegalArgumentException("Una escritura inserta o resuelve un intento, no ambos");
        }
    }

    public static DispatchWrite insert(
            UUID scheduleId,
            int expectedAttemptCount,
            NotificationAttempt attempt,
            DispatchFinish finish,
            Instant now
    ) {
        return new DispatchWrite(scheduleId, expectedAttemptCount, attempt, null, finish, null, now);
    }

    public static DispatchWrite resolve(
            UUID scheduleId,
            int expectedAttemptCount,
            NotificationAttempt attempt,
            DispatchFinish finish,
            Instant retryAt,
            Instant now
    ) {
        return new DispatchWrite(scheduleId, expectedAttemptCount, null, attempt, finish, retryAt, now);
    }

    public static DispatchWrite finishOnly(
            UUID scheduleId,
            int expectedAttemptCount,
            DispatchFinish finish,
            Instant now
    ) {
        return new DispatchWrite(scheduleId, expectedAttemptCount, null, null, finish, null, now);
    }

    /** Número de intentos que tendrá la notificación si la escritura se aplica. */
    public int resultingAttemptCount() {
        return newAttempt == null ? expectedAttemptCount : expectedAttemptCount + 1;
    }
}
