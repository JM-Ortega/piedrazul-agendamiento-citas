package co.edu.unicauca.piedrazul.backend.notifications.infrastructure.scheduler;

import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationSchedule;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.ClaimedSchedule;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.NotificationAttemptRepository;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.NotificationScheduleRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Component
public class ScheduleClaimService {

    private final NotificationScheduleRepository scheduleRepository;
    private final NotificationAttemptRepository attemptRepository;

    public ScheduleClaimService(
            NotificationScheduleRepository scheduleRepository,
            NotificationAttemptRepository attemptRepository
    ) {
        this.scheduleRepository = scheduleRepository;
        this.attemptRepository = attemptRepository;
    }

    /**
     * Reclama los schedules vencidos (bloqueo con {@code SKIP LOCKED}, PENDING → PROCESSING) y
     * fija, en la misma transacción, el número de intentos de cada notificación como cerca de
     * propiedad para el despacho.
     */
    @Transactional
    public List<ClaimedSchedule> claimDueSchedules(
            Instant now,
            int limit
    ) {
        List<NotificationSchedule> schedules =
                scheduleRepository.findDuePending(now, limit);

        List<ClaimedSchedule> claimed = new ArrayList<>(schedules.size());

        for (NotificationSchedule schedule : schedules) {
            schedule.markProcessing(now);
            scheduleRepository.save(schedule);

            claimed.add(new ClaimedSchedule(
                    schedule.getId(),
                    attemptRepository.countByNotificationId(schedule.getNotificationId())
            ));
        }

        return claimed;
    }
}
