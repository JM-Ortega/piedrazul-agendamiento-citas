package co.edu.unicauca.piedrazul.backend.notifications.domain.port.output;

import co.edu.unicauca.piedrazul.backend.notifications.domain.model.Notification;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationAttempt;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationSchedule;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Persistencia guardada del despacho. Cada operación es una transacción que bloquea la fila del
 * schedule y comprueba la propiedad del trabajo antes de escribir:
 *
 * <ul>
 *   <li>el schedule sigue en PROCESSING (sigue reclamado), y</li>
 *   <li>el número de intentos de la notificación es el que el despachador espera (cerca de
 *       intentos): un trabajador desplazado no puede insertar ni resolver por encima de otro.</li>
 * </ul>
 *
 * El estado se lee fresco dentro de la transacción; nunca se fusiona un estado leído fuera de
 * ella. Ninguna operación invoca proveedores.
 */
public interface NotificationDispatchStore {

    /**
     * Toma el trabajo reclamado: si la notificación está PENDING pasa a PROCESSING. Si la
     * notificación falta o ya no admite despacho, cierra el schedule de forma coherente y lo
     * informa como {@link Begin.Status#NOT_DISPATCHABLE}.
     */
    Begin begin(UUID scheduleId, int claimedAttemptCount, Instant now);

    /**
     * Aplica la escritura completa o nada. Si la propiedad se perdió, solo registra el resultado
     * de {@code resolvedAttempt} cuando ese intento sigue en PROCESSING (es un hecho propio, no
     * pisa estado más nuevo) y devuelve {@link WriteResult#OWNERSHIP_LOST}.
     */
    WriteResult write(DispatchWrite write);

    enum WriteResult {
        APPLIED,
        OWNERSHIP_LOST
    }

    record Begin(
            Status status,
            Notification notification,
            NotificationSchedule schedule,
            List<NotificationAttempt> attempts
    ) {
        public enum Status {
            READY,
            OWNERSHIP_LOST,
            NOT_DISPATCHABLE
        }

        public static Begin of(Status status) {
            return new Begin(status, null, null, List.of());
        }
    }
}
