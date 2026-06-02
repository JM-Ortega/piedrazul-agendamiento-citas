package co.edu.unicauca.piedrazul.backend.notifications.domain.port.output;

import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationSchedule;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationScheduleRepository {

    NotificationSchedule save(NotificationSchedule schedule);

    Optional<NotificationSchedule> findById(UUID id);

    List<NotificationSchedule> findDuePending(
            Instant now,
            int limit
    );

    List<NotificationSchedule> findPendingByNotificationId(UUID notificationId);
}