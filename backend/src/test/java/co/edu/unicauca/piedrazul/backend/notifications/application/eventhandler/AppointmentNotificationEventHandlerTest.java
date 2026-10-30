package co.edu.unicauca.piedrazul.backend.notifications.application.eventhandler;

import co.edu.unicauca.piedrazul.backend.appointment.events.AppointmentScheduledEvent;
import co.edu.unicauca.piedrazul.backend.notifications.NotificationModuleApi;
import co.edu.unicauca.piedrazul.backend.notifications.api.ScheduleNotificationCommand;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.AggregateType;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationType;
import co.edu.unicauca.piedrazul.backend.notifications.domain.policy.ReminderScheduleCalculator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Política de avisos de una cita nueva. Datos exclusivamente sintéticos. */
class AppointmentNotificationEventHandlerTest {

    private static final LocalDate APPOINTMENT_DATE = LocalDate.of(2026, 3, 10);
    // 16:00 en America/Bogota (UTC-5, sin horario de verano) del 8 de marzo.
    private static final Instant REMINDER_AT = Instant.parse("2026-03-08T21:00:00Z");

    private final NotificationModuleApi api = mock(NotificationModuleApi.class);
    private final AppointmentScheduledEvent event = new AppointmentScheduledEvent(UUID.randomUUID(), UUID.randomUUID(),
            "Paciente Sintético", "+570000000000", "sintetico@example.test", UUID.randomUUID(), "Dra. Sintética",
            APPOINTMENT_DATE, LocalTime.of(8, 0), "FISIOTERAPIA", UUID.randomUUID());

    @Test
    void newAppointmentSchedulesItsReminderAndNoConfirmation() {
        handlerAt(REMINDER_AT.minusSeconds(86_400)).onAppointmentScheduled(event);

        ArgumentCaptor<ScheduleNotificationCommand> command = ArgumentCaptor.forClass(ScheduleNotificationCommand.class);
        verify(api).schedule(command.capture());
        verify(api, never()).sendNow(any());

        assertThat(command.getValue().type()).isEqualTo(NotificationType.APPOINTMENT_REMINDER_2_DAYS);
        assertThat(command.getValue().scheduledAt()).isEqualTo(REMINDER_AT);
        assertThat(command.getValue().aggregate().type()).isEqualTo(AggregateType.APPOINTMENT);
        assertThat(command.getValue().aggregate().id()).isEqualTo(event.appointmentId());
    }

    @Test
    void reminderStillInTheFutureByAnInstantIsScheduled() {
        handlerAt(REMINDER_AT.minusNanos(1)).onAppointmentScheduled(event);

        verify(api).schedule(any());
    }

    @Test
    void reminderDueExactlyNowIsOmitted() {
        handlerAt(REMINDER_AT).onAppointmentScheduled(event);

        verify(api, never()).schedule(any());
        verify(api, never()).sendNow(any());
    }

    @Test
    void reminderAlreadyPastIsOmitted() {
        handlerAt(REMINDER_AT.plusSeconds(1)).onAppointmentScheduled(event);

        verify(api, never()).schedule(any());
        verify(api, never()).sendNow(any());
    }

    private AppointmentNotificationEventHandler handlerAt(Instant now) {
        return new AppointmentNotificationEventHandler(
                api,
                new ReminderScheduleCalculator(ZoneId.of("America/Bogota")),
                Clock.fixed(now, ZoneOffset.UTC)
        );
    }
}
