package co.edu.unicauca.piedrazul.backend.notifications.application.eventhandler;

import co.edu.unicauca.piedrazul.backend.appointment.events.AppointmentCancelledByStaffEvent;
import co.edu.unicauca.piedrazul.backend.appointment.events.AppointmentRescheduledEvent;
import co.edu.unicauca.piedrazul.backend.appointment.events.AppointmentScheduledEvent;
import co.edu.unicauca.piedrazul.backend.notifications.api.SendNotificationCommand;
import java.util.Map;
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

    @Test
    void staffCancellationSendsOneEmailNoticeOnTheCancelledAppointmentAndNothingElse() {
        AppointmentCancelledByStaffEvent cancelled = new AppointmentCancelledByStaffEvent(UUID.randomUUID(),
                UUID.randomUUID(), "Paciente Sintético", "+570000000000", "sintetico@example.test",
                "Dra. Sintética", LocalDate.of(2026, 3, 10), LocalTime.of(14, 30), "TERAPIA_NEURAL");

        handlerAt(REMINDER_AT).onAppointmentCancelledByStaff(cancelled);

        ArgumentCaptor<SendNotificationCommand> command = ArgumentCaptor.forClass(SendNotificationCommand.class);
        verify(api).sendNow(command.capture());
        verify(api, never()).schedule(any());
        SendNotificationCommand sent = command.getValue();
        assertThat(sent.type()).isEqualTo(NotificationType.APPOINTMENT_CANCELLED);
        assertThat(sent.aggregate().type()).isEqualTo(AggregateType.APPOINTMENT);
        assertThat(sent.aggregate().id()).isEqualTo(cancelled.appointmentId());
        assertThat(sent.recipient().email()).isEqualTo("sintetico@example.test");
        assertThat(sent.recipient().channelPreference().preferredOrder()).isEmpty();
        assertThat(sent.variables()).containsEntry("patientName", "Paciente Sintético")
                .containsEntry("doctorName", "Dra. Sintética")
                .containsEntry("date", "Martes 10 de marzo de 2026")
                .containsEntry("specialty", "Terapia neural");
        assertThat(sent.variables().get("time")).startsWith("2:30").endsWith("m.");
    }

    @Test
    void reschedulingSendsOneNoticeOnThePreviousAppointmentWithBothSetsOfDetailsAndNoReminderChange() {
        AppointmentRescheduledEvent rescheduled = new AppointmentRescheduledEvent(UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), "Paciente Sintético", "+570000000000", null,
                "Dr. Anterior", LocalDate.of(2026, 3, 10), LocalTime.of(8, 0), "FISIOTERAPIA",
                "Dra. Nueva", LocalDate.of(2026, 3, 12), LocalTime.of(15, 0), "QUIROPRAXIA");

        handlerAt(REMINDER_AT).onAppointmentRescheduled(rescheduled);

        ArgumentCaptor<SendNotificationCommand> command = ArgumentCaptor.forClass(SendNotificationCommand.class);
        verify(api).sendNow(command.capture());
        verify(api, never()).schedule(any());
        SendNotificationCommand sent = command.getValue();
        assertThat(sent.type()).isEqualTo(NotificationType.APPOINTMENT_RESCHEDULED);
        assertThat(sent.aggregate().id()).isEqualTo(rescheduled.previousAppointmentId());
        assertThat(sent.variables()).containsAllEntriesOf(Map.of(
                "previousDoctorName", "Dr. Anterior",
                "previousDate", "Martes 10 de marzo de 2026",
                "previousSpecialty", "Fisioterapia",
                "doctorName", "Dra. Nueva",
                "date", "Jueves 12 de marzo de 2026",
                "specialty", "Quiropraxia"));
        assertThat(sent.variables().get("previousTime")).startsWith("8:00");
        assertThat(sent.variables().get("time")).startsWith("3:00");
    }

    private AppointmentNotificationEventHandler handlerAt(Instant now) {
        return new AppointmentNotificationEventHandler(
                api,
                new ReminderScheduleCalculator(ZoneId.of("America/Bogota")),
                Clock.fixed(now, ZoneOffset.UTC)
        );
    }
}
