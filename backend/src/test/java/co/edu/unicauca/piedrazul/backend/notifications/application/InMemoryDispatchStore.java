package co.edu.unicauca.piedrazul.backend.notifications.application;

import co.edu.unicauca.piedrazul.backend.notifications.domain.model.*;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.ClaimedSchedule;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.DispatchWrite;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.NotificationDispatchStore;

import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

/**
 * {@link NotificationDispatchStore} en memoria con las mismas guardas que la implementación JPA
 * (schedule en PROCESSING y cerca de intentos). Guarda copias: lo que el despachador modifica en
 * memoria no cuenta hasta que lo escribe. Solo para pruebas unitarias.
 */
final class InMemoryDispatchStore implements NotificationDispatchStore {

    private final Map<UUID, Notification> notifications = new LinkedHashMap<>();
    private final Map<UUID, NotificationSchedule> schedules = new LinkedHashMap<>();
    private final Map<UUID, NotificationAttempt> attempts = new LinkedHashMap<>();
    final List<DispatchWrite> appliedWrites = new ArrayList<>();

    /** Se ejecuta antes de cada escritura; permite simular otro trabajador o un fallo de base. */
    Consumer<DispatchWrite> beforeWrite = write -> {
    };

    // ── Preparación ─────────────────────────────────────────────────────

    ClaimedSchedule seedAndClaim(Notification notification, Instant now) {
        notifications.put(notification.getId(), copy(notification));
        NotificationSchedule schedule = NotificationSchedule.create(notification.getId(), now, now);
        schedule.markProcessing(now);
        schedules.put(schedule.getId(), schedule);
        return new ClaimedSchedule(schedule.getId(), countFor(notification.getId()));
    }

    /** Reclama de nuevo un schedule que quedó PENDING (reintento programado). */
    ClaimedSchedule reclaim(UUID scheduleId, Instant now) {
        NotificationSchedule schedule = schedules.get(scheduleId);
        schedule.markProcessing(now);
        return new ClaimedSchedule(scheduleId, countFor(schedule.getNotificationId()));
    }

    void putAttempt(NotificationAttempt attempt) {
        attempts.put(attempt.getId(), copy(attempt));
    }

    /** Simula otro proceso (p. ej. una cancelación) que cambia el schedule sin pasar por aquí. */
    void forceScheduleStatus(UUID scheduleId, ScheduleStatus status, Instant now) {
        NotificationSchedule schedule = schedules.get(scheduleId);

        switch (status) {
            case CANCELLED -> schedule.cancel(now);
            case FAILED -> schedule.markFailed(now);
            default -> throw new IllegalArgumentException("Estado no soportado: " + status);
        }
    }

    UUID lastScheduleFor(UUID notificationId) {
        return schedules.values().stream()
                .filter(schedule -> schedule.getNotificationId().equals(notificationId))
                .reduce((first, second) -> second)
                .orElseThrow()
                .getId();
    }

    // ── Consulta ────────────────────────────────────────────────────────

    Notification notification(UUID id) {
        return copy(notifications.get(id));
    }

    NotificationSchedule schedule(UUID id) {
        return copy(schedules.get(id));
    }

    List<NotificationAttempt> attempts() {
        return attempts.values().stream().map(InMemoryDispatchStore::copy).toList();
    }

    // ── Puerto ──────────────────────────────────────────────────────────

    @Override
    public Begin begin(UUID scheduleId, int claimedAttemptCount, Instant now) {
        NotificationSchedule schedule = ownedSchedule(scheduleId, claimedAttemptCount);

        if (schedule == null) {
            return Begin.of(Begin.Status.OWNERSHIP_LOST);
        }

        Notification notification = notifications.get(schedule.getNotificationId());

        if (notification == null) {
            schedule.markFailed(now);
            return Begin.of(Begin.Status.NOT_DISPATCHABLE);
        }

        switch (notification.getStatus()) {
            case PENDING -> notification.markProcessing(now);
            case PROCESSING -> {
            }
            default -> {
                switch (notification.getStatus()) {
                    case ACCEPTED, DELIVERED -> schedule.markSent(now);
                    case CANCELLED -> schedule.cancel(now);
                    case EXPIRED -> schedule.markExpired(now);
                    default -> schedule.markFailed(now);
                }

                notification.redactSensitiveVariables();
                return Begin.of(Begin.Status.NOT_DISPATCHABLE);
            }
        }

        List<NotificationAttempt> current = attempts.values().stream()
                .filter(attempt -> attempt.getNotificationId().equals(notification.getId()))
                .map(InMemoryDispatchStore::copy)
                .toList();

        return new Begin(Begin.Status.READY, copy(notification), copy(schedule), current);
    }

    @Override
    public WriteResult write(DispatchWrite write) {
        beforeWrite.accept(write);

        NotificationSchedule schedule = ownedSchedule(write.scheduleId(), write.expectedAttemptCount());
        Notification notification = schedule == null ? null : notifications.get(schedule.getNotificationId());

        if (schedule == null || notification == null || notification.getStatus() != NotificationStatus.PROCESSING) {
            NotificationAttempt resolved = write.resolvedAttempt();

            if (resolved != null && attempts.containsKey(resolved.getId())
                    && attempts.get(resolved.getId()).getStatus() == AttemptStatus.PROCESSING) {
                attempts.put(resolved.getId(), copy(resolved));
            }

            return WriteResult.OWNERSHIP_LOST;
        }

        // Transacción: se trabaja sobre copias y solo se publican al final.
        Notification nextNotification = copy(notification);
        NotificationSchedule nextSchedule = copy(schedule);

        if (write.newAttempt() != null && attempts.containsKey(write.newAttempt().getId())) {
            throw new IllegalStateException("Intento duplicado");
        }

        if (write.resolvedAttempt() != null) {
            NotificationAttempt stored = attempts.get(write.resolvedAttempt().getId());

            if (stored == null || stored.getStatus() != AttemptStatus.PROCESSING) {
                return WriteResult.OWNERSHIP_LOST;
            }
        }

        write.finish().applyTo(nextNotification, nextSchedule, write.retryAt(), write.now());

        if (write.newAttempt() != null) {
            attempts.put(write.newAttempt().getId(), copy(write.newAttempt()));
        }

        if (write.resolvedAttempt() != null) {
            attempts.put(write.resolvedAttempt().getId(), copy(write.resolvedAttempt()));
        }

        notifications.put(nextNotification.getId(), nextNotification);
        schedules.put(nextSchedule.getId(), nextSchedule);
        appliedWrites.add(write);
        return WriteResult.APPLIED;
    }

    private NotificationSchedule ownedSchedule(UUID scheduleId, int expectedAttemptCount) {
        NotificationSchedule schedule = schedules.get(scheduleId);

        if (schedule == null || schedule.getStatus() != ScheduleStatus.PROCESSING) {
            return null;
        }

        return countFor(schedule.getNotificationId()) == expectedAttemptCount ? schedule : null;
    }

    private int countFor(UUID notificationId) {
        return (int) attempts.values().stream()
                .filter(attempt -> attempt.getNotificationId().equals(notificationId))
                .count();
    }

    // ── Copias ──────────────────────────────────────────────────────────

    private static Notification copy(Notification n) {
        return n == null ? null : Notification.reconstruct(n.getId(), n.getType(), n.getAggregate(), n.getRecipient(),
                n.getChannelPreference(), n.getVariables(), n.getIdempotencyKey(), n.getStatus(),
                n.getCreatedAt(), n.getUpdatedAt());
    }

    private static NotificationSchedule copy(NotificationSchedule s) {
        return s == null ? null : NotificationSchedule.reconstruct(s.getId(), s.getNotificationId(), s.getScheduledAt(),
                s.getStatus(), s.getNextRetryAt(), s.getProcessingStartedAt(), s.getCancelledAt(),
                s.getCreatedAt(), s.getUpdatedAt());
    }

    private static NotificationAttempt copy(NotificationAttempt a) {
        return NotificationAttempt.reconstruct(a.getId(), a.getNotificationId(), a.getScheduleId(), a.getChannel(),
                a.getProviderName(), a.getProviderMessageId(), a.getStatus(), a.getAttemptNumber(),
                a.getFailureType(), a.getErrorCode(), a.getErrorMessage(), a.getSentAt(), a.getAcceptedAt(),
                a.getDeliveredAt(), a.getFailedAt(), a.getCreatedAt(), a.getUpdatedAt());
    }
}
