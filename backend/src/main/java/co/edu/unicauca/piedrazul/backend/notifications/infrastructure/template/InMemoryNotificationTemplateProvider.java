package co.edu.unicauca.piedrazul.backend.notifications.infrastructure.template;

import co.edu.unicauca.piedrazul.backend.notifications.application.exception.TemplateLoadingException;
import co.edu.unicauca.piedrazul.backend.notifications.application.exception.TemplateRenderingException;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationChannel;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationType;
import co.edu.unicauca.piedrazul.backend.notifications.domain.template.NotificationTemplateProvider;
import co.edu.unicauca.piedrazul.backend.notifications.domain.template.TemplateDefinition;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

public class InMemoryNotificationTemplateProvider implements NotificationTemplateProvider {

    private static final Locale DEFAULT_LOCALE = Locale.forLanguageTag("es-CO");

    private final List<TemplateDefinition> templates;

    public InMemoryNotificationTemplateProvider() {
        this.templates = List.of(
                appointmentScheduledConsole(),
                appointmentScheduledSms(),
                appointmentScheduledEmail(),
                appointmentScheduledWhatsApp(),
                appointmentReminderConsole(),
                appointmentReminderSms(),
                appointmentReminderEmail(),
                appointmentReminderWhatsApp(),
                otpConsole(),
                otpSms(),
                otpEmail(),
                otpWhatsApp()
        );
    }

    @Override
    public TemplateDefinition findTemplate(
            NotificationType type,
            NotificationChannel channel,
            Locale locale
    ) {
        Locale effectiveLocale = locale == null ? DEFAULT_LOCALE : locale;

        return templates.stream()
                .filter(template -> template.type() == type)
                .filter(template -> template.channel() == channel)
                .filter(template -> template.locale().equals(effectiveLocale)
                        || template.locale().equals(DEFAULT_LOCALE))
                .findFirst()
                .orElseThrow(() -> new TemplateRenderingException(
                        type,
                        channel,
                        effectiveLocale
                ));
    }

    private TemplateDefinition appointmentScheduledConsole() {
        return new TemplateDefinition(
                NotificationType.APPOINTMENT_SCHEDULED,
                NotificationChannel.CONSOLE,
                DEFAULT_LOCALE,
                "appointment-scheduled-console",
                null,
                "[NOTIFICATION] Cita agendada para {{patientName}} con {{doctorName}} el {{date}} a las {{time}}.",
                null,
                null,
                null
        );
    }

    private TemplateDefinition appointmentScheduledSms() {
        return new TemplateDefinition(
                NotificationType.APPOINTMENT_SCHEDULED,
                NotificationChannel.SMS,
                DEFAULT_LOCALE,
                "appointment-scheduled-sms",
                null,
                "Piedrazul: tu cita fue agendada para el {{date}} a las {{time}} con {{doctorName}}.",
                null,
                null,
                null
        );
    }

    private TemplateDefinition appointmentScheduledEmail() {
        return new TemplateDefinition(
                NotificationType.APPOINTMENT_SCHEDULED,
                NotificationChannel.EMAIL,
                DEFAULT_LOCALE,
                "appointment-scheduled-email",
                "Cita médica agendada",
                "Hola {{patientName}}, tu cita fue agendada para el {{date}} a las {{time}} con {{doctorName}}.",
                loadTemplate("appointment-scheduled-email.html"),
                null,
                null
        );
    }

    private TemplateDefinition appointmentScheduledWhatsApp() {
        return new TemplateDefinition(
                NotificationType.APPOINTMENT_SCHEDULED,
                NotificationChannel.WHATSAPP,
                DEFAULT_LOCALE,
                "appointment-scheduled-whatsapp",
                null,
                null,
                null,
                "appointment_scheduled",
                List.of("patientName", "doctorName", "date", "time")
        );
    }

    private TemplateDefinition appointmentReminderConsole() {
        return new TemplateDefinition(
                NotificationType.APPOINTMENT_REMINDER_2_DAYS,
                NotificationChannel.CONSOLE,
                DEFAULT_LOCALE,
                "appointment-reminder-2-days-console",
                null,
                "[NOTIFICATION] Recordatorio: {{patientName}} tiene cita con {{doctorName}} el {{date}} a las {{time}}.",
                null,
                null,
                null
        );
    }

    private TemplateDefinition appointmentReminderSms() {
        return new TemplateDefinition(
                NotificationType.APPOINTMENT_REMINDER_2_DAYS,
                NotificationChannel.SMS,
                DEFAULT_LOCALE,
                "appointment-reminder-2-days-sms",
                null,
                "Piedrazul: recuerda tu cita el {{date}} a las {{time}} con {{doctorName}}.",
                null,
                null,
                null
        );
    }

    private TemplateDefinition appointmentReminderEmail() {
        return new TemplateDefinition(
                NotificationType.APPOINTMENT_REMINDER_2_DAYS,
                NotificationChannel.EMAIL,
                DEFAULT_LOCALE,
                "appointment-reminder-2-days-email",
                "Recordatorio de cita médica",
                "Hola {{patientName}}, recuerda tu cita el {{date}} a las {{time}} con {{doctorName}}.",
                loadTemplate("appointment-reminder-2-days-email.html"),
                null,
                null
        );
    }

    private TemplateDefinition appointmentReminderWhatsApp() {
        return new TemplateDefinition(
                NotificationType.APPOINTMENT_REMINDER_2_DAYS,
                NotificationChannel.WHATSAPP,
                DEFAULT_LOCALE,
                "appointment-reminder-2-days-whatsapp",
                null,
                null,
                null,
                "appointment_reminder_2_days",
                List.of("patientName", "doctorName", "date", "time")
        );
    }

    private TemplateDefinition otpConsole() {
        return new TemplateDefinition(
                NotificationType.OTP_CODE,
                NotificationChannel.CONSOLE,
                DEFAULT_LOCALE,
                "otp-console",
                null,
                "[NOTIFICATION] Código OTP para {{destination}}: {{code}}. Expira en {{expirationMinutes}} minutos.",
                null,
                null,
                null
        );
    }

    private TemplateDefinition otpSms() {
        return new TemplateDefinition(
                NotificationType.OTP_CODE,
                NotificationChannel.SMS,
                DEFAULT_LOCALE,
                "otp-sms",
                null,
                "Piedrazul: tu código es {{code}}. Expira en {{expirationMinutes}} minutos.",
                null,
                null,
                null
        );
    }

    private TemplateDefinition otpEmail() {
        return new TemplateDefinition(
                NotificationType.OTP_CODE,
                NotificationChannel.EMAIL,
                DEFAULT_LOCALE,
                "otp-email",
                "Código de verificación",
                "Tu código de verificación es {{code}}. Expira en {{expirationMinutes}} minutos.",
                loadTemplate("otp-email.html"),
                null,
                null
        );
    }

    private TemplateDefinition otpWhatsApp() {
        return new TemplateDefinition(
                NotificationType.OTP_CODE,
                NotificationChannel.WHATSAPP,
                DEFAULT_LOCALE,
                "otp-whatsapp",
                null,
                null,
                null,
                "otp_code",
                List.of("code", "expirationMinutes")
        );
    }

    private String loadTemplate(String filename) {
        String path = "/notifications/templates/" + filename;

        try (InputStream inputStream = getClass().getResourceAsStream(path)) {
            if (inputStream == null) {
                throw new TemplateLoadingException(
                        "No se encontró el template en classpath: " + path
                );
            }

            return new String(
                    inputStream.readAllBytes(),
                    StandardCharsets.UTF_8
            );
        } catch (TemplateLoadingException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new TemplateLoadingException(
                    "Error cargando template: " + path,
                    exception
            );
        }
    }
}