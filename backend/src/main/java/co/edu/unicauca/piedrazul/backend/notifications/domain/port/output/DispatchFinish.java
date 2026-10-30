package co.edu.unicauca.piedrazul.backend.notifications.domain.port.output;

import co.edu.unicauca.piedrazul.backend.notifications.domain.model.Notification;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationSchedule;

import java.time.Instant;
import java.util.Objects;

/** Transición de notificación y schedule que acompaña a una escritura del despacho. */
public enum DispatchFinish {

    /** Sin cambio: el despacho sigue con otro canal en la misma pasada. */
    CONTINUE,

    /** El proveedor aceptó: notificación ACCEPTED, schedule SENT. */
    ACCEPTED,

    /** Terminó sin aceptación confirmada (incluida la aceptación desconocida). */
    FAILED,

    /** El contenido caducó antes de entregarse al proveedor. */
    EXPIRED,

    /** Reintento programado: el schedule vuelve a PENDING; la notificación sigue en curso. */
    RETRY;

    /** Ya no queda reintento ni canal que necesite las variables secretas. */
    public boolean endsDispatch() {
        return this == ACCEPTED || this == FAILED || this == EXPIRED;
    }

    public void applyTo(
            Notification notification,
            NotificationSchedule schedule,
            Instant retryAt,
            Instant now
    ) {
        switch (this) {
            case CONTINUE -> {
            }
            case ACCEPTED -> {
                notification.markAccepted(now);
                schedule.markSent(now);
            }
            case FAILED -> {
                notification.markFailed(now);
                schedule.markFailed(now);
            }
            case EXPIRED -> {
                notification.markExpired(now);
                schedule.markExpired(now);
            }
            case RETRY -> schedule.scheduleRetry(
                    Objects.requireNonNull(retryAt, "La fecha del reintento es obligatoria"),
                    now
            );
        }

        if (endsDispatch()) {
            notification.redactSensitiveVariables();
        }
    }
}
