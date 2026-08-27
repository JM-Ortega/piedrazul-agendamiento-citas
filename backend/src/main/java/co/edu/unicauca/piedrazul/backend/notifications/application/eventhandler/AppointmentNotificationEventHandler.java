package co.edu.unicauca.piedrazul.backend.notifications.application.eventhandler;

import co.edu.unicauca.piedrazul.backend.appointment.events.AppointmentScheduledEvent;
import co.edu.unicauca.piedrazul.backend.notifications.NotificationModuleApi;
import co.edu.unicauca.piedrazul.backend.notifications.api.ScheduleNotificationCommand;
import co.edu.unicauca.piedrazul.backend.notifications.api.SendNotificationCommand;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.AggregateReference;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.AggregateType;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.ChannelPreference;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationType;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.RecipientSnapshot;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.RecipientType;
import co.edu.unicauca.piedrazul.backend.notifications.domain.policy.ReminderScheduleCalculator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class AppointmentNotificationEventHandler {

    private final NotificationModuleApi notificationModuleApi;
    private final ReminderScheduleCalculator reminderCalculator;
    private final Clock clock;

    public AppointmentNotificationEventHandler(
            NotificationModuleApi notificationModuleApi,
            ReminderScheduleCalculator reminderCalculator,
            @Qualifier("notificationClock") Clock clock
    ) {
        this.notificationModuleApi = notificationModuleApi;
        this.reminderCalculator = reminderCalculator;
        this.clock = clock;
    }

    @ApplicationModuleListener
    public void onAppointmentScheduled(AppointmentScheduledEvent event) {
        AggregateReference aggregate = new AggregateReference(
                AggregateType.APPOINTMENT,
                event.appointmentId()
        );

        RecipientSnapshot recipient = new RecipientSnapshot(
                event.patientId(),
                RecipientType.PATIENT,
                event.patientName(),
                event.patientPhone(),
                event.patientEmail(),
                Locale.forLanguageTag("es-CO"),
                new ChannelPreference(List.of())
        );

        Map<String, String> variables = buildVariables(event);

        Instant now = Instant.now(clock);

        // 1. Confirmación inmediata
        notificationModuleApi.sendNow(
                new SendNotificationCommand(
                        NotificationType.APPOINTMENT_SCHEDULED,
                        aggregate,
                        recipient,
                        variables
                )
        );

        // 2. Recordatorio 2 días antes a las 4pm
        Instant reminderAt = reminderCalculator
                .calculateTwoDaysBeforeAtFourPm(event.appointmentDate());

        if (reminderCalculator.isFuture(reminderAt, now)) {
            notificationModuleApi.schedule(
                    new ScheduleNotificationCommand(
                            NotificationType.APPOINTMENT_REMINDER_2_DAYS,
                            aggregate,
                            recipient,
                            reminderAt,
                            variables
                    )
            );
        }
    }

    private Map<String, String> buildVariables(AppointmentScheduledEvent event) {
        return Map.of(
                "patientName", event.patientName(),
                "doctorName", event.doctorName(),
                "date", event.appointmentDate().toString(),
                "time", event.appointmentTime().toString(),
                "specialty", event.specialty()
        );
    }
}