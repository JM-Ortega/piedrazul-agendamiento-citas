package co.edu.unicauca.piedrazul.backend.notifications.application;

import co.edu.unicauca.piedrazul.backend.notifications.domain.model.Notification;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationSchedule;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.NotificationScheduleRepository;
import co.edu.unicauca.piedrazul.backend.notifications.logging.NotificationLogContext;
import co.edu.unicauca.piedrazul.backend.notifications.logging.NotificationLogger;

import java.time.Instant;
import java.util.Objects;

public class NotificationSchedulerService {

    private final NotificationFactory notificationFactory;
    private final NotificationScheduleRepository scheduleRepository;

    public NotificationSchedulerService(
            NotificationFactory notificationFactory,
            NotificationScheduleRepository scheduleRepository
    ) {
        this.notificationFactory = notificationFactory;
        this.scheduleRepository = scheduleRepository;
    }

    public NotificationSchedule scheduleNow(
            Notification notification,
            Instant now
    ) {
        Objects.requireNonNull(notification, "La notificación es obligatoria");
        Objects.requireNonNull(now, "La fecha actual es obligatoria");

        NotificationSchedule schedule = notificationFactory.createImmediateSchedule(
                notification,
                now
        );

        NotificationSchedule savedSchedule = scheduleRepository.save(schedule);

        NotificationLogger.scheduled(
                logContext(notification, savedSchedule),
                savedSchedule.getScheduledAt()
        );

        return savedSchedule;
    }

    public NotificationSchedule scheduleAt(
            Notification notification,
            Instant scheduledAt,
            Instant now
    ) {
        Objects.requireNonNull(notification, "La notificación es obligatoria");
        Objects.requireNonNull(scheduledAt, "La fecha programada es obligatoria");
        Objects.requireNonNull(now, "La fecha actual es obligatoria");

        NotificationSchedule schedule = notificationFactory.createDelayedSchedule(
                notification,
                scheduledAt,
                now
        );

        NotificationSchedule savedSchedule = scheduleRepository.save(schedule);

        NotificationLogger.scheduled(
                logContext(notification, savedSchedule),
                savedSchedule.getScheduledAt()
        );

        return savedSchedule;
    }

    private NotificationLogContext logContext(
            Notification notification,
            NotificationSchedule schedule
    ) {
        return NotificationLogContext
                .builder(notification.getId(), notification.getType())
                .aggregate(
                        notification.getAggregate().type(),
                        notification.getAggregate().id()
                )
                .build()
                .withSchedule(schedule.getId());
    }
}