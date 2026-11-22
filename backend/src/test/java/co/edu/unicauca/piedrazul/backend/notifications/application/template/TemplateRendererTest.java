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

    private static final Map<String, String> NOTICE_VARIABLES = Map.ofEntries(
            Map.entry("patientName", "José <b>Peña</b>"), Map.entry("doctorName", "Dra. Núñez & Co"),
            Map.entry("date", "Martes 10 de marzo de 2026"), Map.entry("time", "2:30 p. m."),
            Map.entry("specialty", "Terapia neural"),
            Map.entry("previousDoctorName", "Dr. Anterior"), Map.entry("previousDate", "Lunes 9 de marzo de 2026"),
            Map.entry("previousTime", "9:00 a. m."), Map.entry("previousSpecialty", "Fisioterapia"));

    @Test
    void cancellationEmailRendersHtmlAndTextEscapedAndComplete() {
        EmailMessage message = (EmailMessage) renderer.render(
                notification(NotificationType.APPOINTMENT_CANCELLED), NotificationChannel.EMAIL, NOTICE_VARIABLES);

        assertThat(message.subject()).isEqualTo("Cita médica cancelada");
        assertThat(message.htmlBody()).doesNotContain("{{").doesNotContain("<b>Peña</b>")
                .contains("José &lt;b&gt;Peña&lt;/b&gt;", "Dra. Núñez &amp; Co", "Martes 10 de marzo de 2026",
                        "2:30 p. m.", "Terapia neural", "viewport", "<title>Cita médica cancelada</title>")
                .doesNotContain("http://", "https://");
        assertThat(message.textBody()).doesNotContain("{{")
                .contains("tu cita médica fue cancelada", "Martes 10 de marzo de 2026", "2:30 p. m.",
                        "Dra. Núñez & Co", "portal de pacientes", "siempre que el agendamiento en línea esté disponible",
                        "comunicarte con el centro médico")
                .doesNotContain("a solicitud", "garantiza");
        assertThat(message.htmlBody()).contains("portal de pacientes",
                "siempre que el agendamiento en línea esté disponible", "comunicarte con el centro médico");
    }

    @Test
    void reschedulingEmailShowsBeforeAndNowAndStatesTheAppointmentIsStillValid() {
        EmailMessage message = (EmailMessage) renderer.render(
                notification(NotificationType.APPOINTMENT_RESCHEDULED), NotificationChannel.EMAIL, NOTICE_VARIABLES);

        assertThat(message.subject()).isEqualTo("Tu cita médica fue reprogramada");
        assertThat(message.htmlBody()).doesNotContain("{{")
                .contains("Antes", "Ahora", "Lunes 9 de marzo de 2026", "9:00 a. m.", "Dr. Anterior",
                        "Martes 10 de marzo de 2026", "2:30 p. m.", "Dra. Núñez &amp; Co", "fue reprogramada",
                        "A continuación encontrarás los datos anteriores y los nuevos.",
                        "no garantiza otro horario", "portal de pacientes")
                .doesNotContain("fue cancelada", "sigue vigente");
        assertThat(message.textBody()).doesNotContain("{{")
                .contains("Antes:", "Ahora:", "Lunes 9 de marzo de 2026", "Martes 10 de marzo de 2026",
                        "fue reprogramada", "Dr. Anterior", "Dra. Núñez & Co",
                        "A continuación encontrarás los datos anteriores y los nuevos.",
                        "comunícate con el centro médico para solicitar otro cambio",
                        "cancelar esta cita desde el portal de pacientes",
                        "si el agendamiento en línea está disponible", "no garantiza otro horario");
        assertThat(message.htmlBody().indexOf("Antes")).isLessThan(message.htmlBody().indexOf("Ahora"));
    }

    private static final List<String> EMAIL_TEMPLATES = List.of("otp-email.html",
            "appointment-reminder-2-days-email.html", "appointment-cancelled-email.html",
            "appointment-rescheduled-email.html", "appointment-scheduled-email.html");

    @Test
    void allFiveEmailTemplatesShareTheBrandHeaderAndFooter() throws Exception {
        for (String name : EMAIL_TEMPLATES) {
            String html = new String(getClass().getResourceAsStream("/notifications/templates/" + name)
                    .readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(html).as(name)
                    .contains(">Piedra Azul Salud</p>", "color:#e5eef9;font-size:14px;\">Centro Médico",
                            "background-color:#215c98", "background:#f9f9f9",
                            "color:#666666;text-align:center;\">Piedra Azul Salud · Centro Médico</p>",
                            "viewport", "max-width:600px")
                    .doesNotContain("Piedrazul", "©", "2026", "#aaaaaa", "#a8c4e8", "width=\"600\"");
        }
    }

    @Test
    void reminderEmailKeepsItsPurposeAndStatesOnlyWhatPatientsCanDo() {
        Map<String, String> vars = Map.of("patientName", "José <b>Peña</b>", "doctorName", "Dra. Núñez & Co",
                "date", "Martes 10 de marzo de 2026", "time", "2:30 p. m.");
        EmailMessage message = (EmailMessage) renderer.render(
                notification(NotificationType.APPOINTMENT_REMINDER_2_DAYS), NotificationChannel.EMAIL, vars);

        for (String body : List.of(message.htmlBody(), message.textBody())) {
            assertThat(body).doesNotContain("{{").contains("en 2 días", "15 minutos de anticipación",
                    "Martes 10 de marzo de 2026", "2:30 p. m.",
                    "puedes hacerlo desde el portal de pacientes, siempre que cumpla las condiciones de cancelación",
                    "Para solicitar un cambio de fecha u hora, comunícate con el centro médico.")
                    .doesNotContain("reprogramar tu cita");
        }
        assertThat(message.htmlBody()).contains("José &lt;b&gt;Peña&lt;/b&gt;", "Dra. Núñez &amp; Co");
        assertThat(message.textBody()).contains("José <b>Peña</b>", "Dra. Núñez & Co");
    }

    @Test
    void otpEmailKeepsInstructionsExpirationAndSecurityNoticeInBothFormats() {
        EmailMessage message = (EmailMessage) renderer.render(
                notification(NotificationType.OTP_CODE), NotificationChannel.EMAIL,
                Map.of("code", "000111", "expirationMinutes", "5"));

        assertThat(message.htmlBody()).doesNotContain("{{").contains("000111", "<strong>5 minutos</strong>",
                "Si no solicitaste este código, ignora este mensaje.", "Usa este código para verificar tu identidad.");
        assertThat(message.textBody()).doesNotContain("{{").contains("000111", "Expira en 5 minutos",
                "Si no solicitaste este código, ignora este mensaje.", "Usa este código para verificar tu identidad.");
    }

    @Test
    void noticeConsoleMessagesAreGenericSensitiveAndCarryNoAppointmentData() {
        for (NotificationType type : List.of(NotificationType.APPOINTMENT_CANCELLED, NotificationType.APPOINTMENT_RESCHEDULED)) {
            ConsoleLogMessage message = (ConsoleLogMessage) renderer.render(
                    notification(type), NotificationChannel.CONSOLE, NOTICE_VARIABLES);

            assertThat(message.sensitive()).isTrue();
            assertThat(message.message()).startsWith("[NOTIFICATION]")
                    .doesNotContain("José", "Núñez", "marzo", "Anterior");
        }
    }

    @Test
    void noticesHaveNoSmsOrWhatsAppTemplate() {
        for (NotificationType type : List.of(NotificationType.APPOINTMENT_CANCELLED, NotificationType.APPOINTMENT_RESCHEDULED)) {
            for (NotificationChannel channel : List.of(NotificationChannel.SMS, NotificationChannel.WHATSAPP)) {
                org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> renderer.render(notification(type), channel, NOTICE_VARIABLES))
                        .isInstanceOf(co.edu.unicauca.piedrazul.backend.notifications.application.exception.TemplateRenderingException.class);
            }
        }
    }

    @Test
    void appointmentTypesIdentifyAPersonAndOtpDoesNot() {
        assertThat(NotificationType.APPOINTMENT_CANCELLED.containsIdentifiableContent()).isTrue();
        assertThat(NotificationType.APPOINTMENT_RESCHEDULED.containsIdentifiableContent()).isTrue();
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
