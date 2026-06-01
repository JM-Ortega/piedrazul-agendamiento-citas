package co.edu.unicauca.piedrazul.backend.notifications.infrastructure.scheduler;

import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationSchedule;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.NotificationScheduleRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Component
public class ScheduleClaimService {

    private final NotificationScheduleRepository scheduleRepository;

    public ScheduleClaimService(
            NotificationScheduleRepository scheduleRepository
    ) {
        this.scheduleRepository = scheduleRepository;
    }

    @Transactional
    public List<NotificationSchedule> claimDueSchedules(
            Instant now,
            int limit
    ) {
        List<NotificationSchedule> schedules =
                scheduleRepository.findDuePending(now, limit);

        for (NotificationSchedule schedule : schedules) {
            schedule.markProcessing(now);
            scheduleRepository.save(schedule);
        }

        return schedules;
    }

    @Transactional
    public void markScheduleFailed(
            NotificationSchedule schedule,
            Instant now
    ) {
        schedule.markFailed(now);
        scheduleRepository.save(schedule);
    }
}