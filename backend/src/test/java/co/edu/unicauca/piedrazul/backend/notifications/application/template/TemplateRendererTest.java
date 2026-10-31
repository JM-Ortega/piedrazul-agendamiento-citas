package co.edu.unicauca.piedrazul.backend.notifications.application.template;

import co.edu.unicauca.piedrazul.backend.notifications.domain.message.ChannelMessage;
import co.edu.unicauca.piedrazul.backend.notifications.domain.message.ConsoleLogMessage;
import co.edu.unicauca.piedrazul.backend.notifications.domain.message.EmailMessage;
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
    void appointmentConsoleMessageIsClassifiedAsSensitive() {
        ChannelMessage message = renderer.render(
                notification(NotificationType.APPOINTMENT_REMINDER_2_DAYS),
                NotificationChannel.CONSOLE,
                Map.of("patientName", "Paciente Sintético")
        );

        assertThat(((ConsoleLogMessage) message).sensitive()).isTrue();
    }

    @Test
    void emailVariablesAreEscapedInHtmlOnlyAndKeepTheirAccents() {
        String hostile = "José <script>alert('x')</script> & \"Peña\"";

        EmailMessage message = (EmailMessage) renderer.render(
                notification(NotificationType.APPOINTMENT_REMINDER_2_DAYS),
                NotificationChannel.EMAIL,
                Map.of("patientName", hostile, "doctorName", "Dra. Núñez", "date", "2026-01-10", "time", "08:00")
        );

        assertThat(message.htmlBody())
                .contains("José &lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt; &amp; &quot;Peña&quot;")
                .contains("Dra. Núñez")
                .doesNotContain("<script>");
        assertThat(message.textBody()).contains(hostile);
        assertThat(message.subject()).isEqualTo("Recordatorio de cita médica");
        assertThat(message.htmlBody()).contains("<strong>Médico:</strong>");
    }

    @Test
    void anEscapedValueStaysOutOfOtherPlaceholders() {
        EmailMessage message = (EmailMessage) renderer.render(
                notification(NotificationType.OTP_CODE),
                NotificationChannel.EMAIL,
                Map.of("code", "000111", "expirationMinutes", "5")
        );

        assertThat(message.htmlBody()).contains("000111").doesNotContain("{{");
    }

    @Test
    void appointmentTypesIdentifyAPersonAndOtpDoesNot() {
        assertThat(NotificationType.APPOINTMENT_REMINDER_2_DAYS.containsIdentifiableContent()).isTrue();
        assertThat(NotificationType.APPOINTMENT_SCHEDULED.containsIdentifiableContent()).isTrue();
        assertThat(NotificationType.OTP_CODE.containsIdentifiableContent()).isFalse();
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
