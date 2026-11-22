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
                appointmentCancelledConsole(),
                appointmentCancelledEmail(),
                appointmentRescheduledConsole(),
                appointmentRescheduledEmail(),
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
                """
                Hola {{patientName}}, te recordamos que tienes una cita médica en 2 días.

                Médico: {{doctorName}}
                Fecha: {{date}}
                Hora: {{time}}

                Por favor llega con 15 minutos de anticipación y trae tus documentos de identidad.

                Si necesitas cancelar tu cita, puedes hacerlo desde el portal de pacientes, siempre que cumpla las condiciones de cancelación. Para solicitar un cambio de fecha u hora, comunícate con el centro médico.
                """,
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

    // Los avisos de cancelación y reprogramación solo tienen correo y consola. La consola es
    // diagnóstico genérico: no lleva nombres ni datos de la cita.
    private TemplateDefinition appointmentCancelledConsole() {
        return new TemplateDefinition(
                NotificationType.APPOINTMENT_CANCELLED,
                NotificationChannel.CONSOLE,
                DEFAULT_LOCALE,
                "appointment-cancelled-console",
                null,
                "[NOTIFICATION] Aviso de cancelación de cita.",
                null,
                null,
                null
        );
    }

    private TemplateDefinition appointmentCancelledEmail() {
        return new TemplateDefinition(
                NotificationType.APPOINTMENT_CANCELLED,
                NotificationChannel.EMAIL,
                DEFAULT_LOCALE,
                "appointment-cancelled-email",
                "Cita médica cancelada",
                """
                Hola {{patientName}}, tu cita médica fue cancelada.

                Cita cancelada:
                Fecha: {{date}}
                Hora: {{time}}
                Médico: {{doctorName}}
                Especialidad: {{specialty}}

                Si deseas programar otra cita, puedes hacerlo desde el portal de pacientes, siempre que el agendamiento en línea esté disponible, o comunicarte con el centro médico.
                """,
                loadTemplate("appointment-cancelled-email.html"),
                null,
                null
        );
    }

    private TemplateDefinition appointmentRescheduledConsole() {
        return new TemplateDefinition(
                NotificationType.APPOINTMENT_RESCHEDULED,
                NotificationChannel.CONSOLE,
                DEFAULT_LOCALE,
                "appointment-rescheduled-console",
                null,
                "[NOTIFICATION] Aviso de reprogramación de cita.",
                null,
                null,
                null
        );
    }

    private TemplateDefinition appointmentRescheduledEmail() {
        return new TemplateDefinition(
                NotificationType.APPOINTMENT_RESCHEDULED,
                NotificationChannel.EMAIL,
                DEFAULT_LOCALE,
                "appointment-rescheduled-email",
                "Tu cita médica fue reprogramada",
                """
                Hola {{patientName}}, tu cita médica fue reprogramada. A continuación encontrarás los datos anteriores y los nuevos.

                Antes:
                Fecha: {{previousDate}}
                Hora: {{previousTime}}
                Médico: {{previousDoctorName}}
                Especialidad: {{previousSpecialty}}

                Ahora:
                Fecha: {{date}}
                Hora: {{time}}
                Médico: {{doctorName}}
                Especialidad: {{specialty}}

                Si la nueva fecha u hora no te conviene, comunícate con el centro médico para solicitar otro cambio. También puedes cancelar esta cita desde el portal de pacientes y solicitar una nueva, si el agendamiento en línea está disponible. Ten en cuenta que cancelar tu cita no garantiza otro horario.
                """,
                loadTemplate("appointment-rescheduled-email.html"),
                null,
                null
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
                """
                Usa este código para verificar tu identidad.

                Tu código de verificación es {{code}}. Expira en {{expirationMinutes}} minutos.

                Si no solicitaste este código, ignora este mensaje.
                """,
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