package co.edu.unicauca.piedrazul.backend.notifications.application.eventhandler;

import co.edu.unicauca.piedrazul.backend.appointment.events.AppointmentCancelledByStaffEvent;
import co.edu.unicauca.piedrazul.backend.appointment.events.AppointmentRescheduledEvent;
import co.edu.unicauca.piedrazul.backend.appointment.events.AppointmentScheduledEvent;
import co.edu.unicauca.piedrazul.backend.notifications.NotificationModuleApi;
import co.edu.unicauca.piedrazul.backend.notifications.api.ScheduleNotificationCommand;
import co.edu.unicauca.piedrazul.backend.notifications.api.SendNotificationCommand;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.AggregateReference;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.AggregateType;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.ChannelPreference;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationType;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.RecipientSnapshot;
import co.edu.unicauca.piedrazul.backend.notifications.domain.policy.ReminderScheduleCalculator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class AppointmentNotificationEventHandler {

    private static final Locale ES_CO = Locale.forLanguageTag("es-CO");
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("EEEE d 'de' MMMM 'de' yyyy", ES_CO);
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("h:mm a", ES_CO);

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
                event.patientName(),
                event.patientPhone(),
                event.patientEmail(),
                Locale.forLanguageTag("es-CO"),
                new ChannelPreference(List.of())
        );

        Map<String, String> variables = buildVariables(event);

        Instant now = Instant.now(clock);

        // Recordatorio 2 días antes a las 4pm
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

    /** Aviso al paciente de una cancelación hecha por el personal. Solo correo (y consola). */
    @ApplicationModuleListener
    public void onAppointmentCancelledByStaff(AppointmentCancelledByStaffEvent event) {
        Map<String, String> variables = Map.of(
                "patientName", event.patientName(),
                "doctorName", event.doctorName(),
                "date", formatDate(event.appointmentDate()),
                "time", formatTime(event.appointmentTime()),
                "specialty", specialtyLabel(event.specialty())
        );

        notificationModuleApi.sendNow(new SendNotificationCommand(
                NotificationType.APPOINTMENT_CANCELLED,
                new AggregateReference(AggregateType.APPOINTMENT, event.appointmentId()),
                recipient(event.patientId(), event.patientName(), event.patientPhone(), event.patientEmail()),
                variables
        ));
    }

    /**
     * Aviso de reagendamiento. Se asocia a la cita original (la cancelada): su clave de
     * idempotencia es distinta de la del recordatorio de la cita nueva.
     */
    @ApplicationModuleListener
    public void onAppointmentRescheduled(AppointmentRescheduledEvent event) {
        Map<String, String> variables = Map.of(
                "patientName", event.patientName(),
                "previousDoctorName", event.previousDoctorName(),
                "previousDate", formatDate(event.previousDate()),
                "previousTime", formatTime(event.previousTime()),
                "previousSpecialty", specialtyLabel(event.previousSpecialty()),
                "doctorName", event.doctorName(),
                "date", formatDate(event.appointmentDate()),
                "time", formatTime(event.appointmentTime()),
                "specialty", specialtyLabel(event.specialty())
        );

        notificationModuleApi.sendNow(new SendNotificationCommand(
                NotificationType.APPOINTMENT_RESCHEDULED,
                new AggregateReference(AggregateType.APPOINTMENT, event.previousAppointmentId()),
                recipient(event.patientId(), event.patientName(), event.patientPhone(), event.patientEmail()),
                variables
        ));
    }

    private static RecipientSnapshot recipient(
            java.util.UUID patientId, String name, String phone, String email) {
        return new RecipientSnapshot(
                patientId,
                name,
                phone,
                email,
                ES_CO,
                new ChannelPreference(List.of())
        );
    }

    private static String formatDate(LocalDate date) {
        String text = DATE_FORMAT.format(date);
        return text.substring(0, 1).toUpperCase(ES_CO) + text.substring(1);
    }

    private static String formatTime(LocalTime time) {
        return TIME_FORMAT.format(time);
    }

    /** FISIOTERAPIA, TERAPIA_NEURAL -> "Fisioterapia", "Terapia neural". */
    private static String specialtyLabel(String code) {
        String text = code.replace('_', ' ').toLowerCase(ES_CO);
        return text.substring(0, 1).toUpperCase(ES_CO) + text.substring(1);
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