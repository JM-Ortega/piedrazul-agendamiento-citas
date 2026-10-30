package co.edu.unicauca.piedrazul.backend.notifications.application.template;

import co.edu.unicauca.piedrazul.backend.notifications.domain.message.ChannelMessage;
import co.edu.unicauca.piedrazul.backend.notifications.domain.message.ConsoleLogMessage;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.*;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.template.InMemoryNotificationTemplateProvider;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateRendererTest {

    private final TemplateRenderer renderer = new TemplateRenderer(new InMemoryNotificationTemplateProvider());

    @Test
    void otpConsoleMessageIsClassifiedAsSensitive() {
        ChannelMessage message = renderer.render(
                notification(NotificationType.OTP_CODE),
                NotificationChannel.CONSOLE,
                Map.of("code", "000111", "expirationMinutes", "5", "destination", "+570000000000")
        );

        assertThat(message).isInstanceOf(ConsoleLogMessage.class);
        assertThat(((ConsoleLogMessage) message).sensitive()).isTrue();
    }

    @Test
    void appointmentConsoleMessageIsNotClassifiedAsSensitive() {
        ChannelMessage message = renderer.render(
                notification(NotificationType.APPOINTMENT_REMINDER_2_DAYS),
                NotificationChannel.CONSOLE,
                Map.of("patientName", "Paciente Sintético")
        );

        assertThat(((ConsoleLogMessage) message).sensitive()).isFalse();
    }

    @Test
    void onlyOtpTypeContainsSensitiveContent() {
        assertThat(NotificationType.OTP_CODE.containsSensitiveContent()).isTrue();
        assertThat(NotificationType.APPOINTMENT_SCHEDULED.containsSensitiveContent()).isFalse();
        assertThat(NotificationType.APPOINTMENT_REMINDER_2_DAYS.containsSensitiveContent()).isFalse();
    }

    private Notification notification(NotificationType type) {
        return Notification.create(
                type,
                new AggregateReference(AggregateType.VERIFICATION, UUID.randomUUID()),
                new RecipientSnapshot(UUID.randomUUID(), "Sintético", "+570000000000", null,
                        Locale.forLanguageTag("es-CO"), null),
                new ChannelPreference(List.of(NotificationChannel.CONSOLE)),
                Map.of(),
                Instant.now()
        );
    }
}
