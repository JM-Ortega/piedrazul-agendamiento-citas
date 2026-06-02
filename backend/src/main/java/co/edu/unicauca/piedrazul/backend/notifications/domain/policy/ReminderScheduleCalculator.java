package co.edu.unicauca.piedrazul.backend.notifications.domain.policy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

public class ReminderScheduleCalculator {

    private static final int REMINDER_DAYS_BEFORE = 2;
    private static final LocalTime REMINDER_TIME = LocalTime.of(16, 0);

    private final ZoneId zoneId;

    public ReminderScheduleCalculator(ZoneId zoneId) {
        this.zoneId = zoneId;
    }

    public Instant calculateTwoDaysBeforeAtFourPm(
            LocalDate appointmentDate
    ) {
        return appointmentDate
                .minusDays(REMINDER_DAYS_BEFORE)
                .atTime(REMINDER_TIME)
                .atZone(zoneId)
                .toInstant();
    }

    public boolean isFuture(Instant scheduledAt, Instant now) {
        return scheduledAt.isAfter(now);
    }
}