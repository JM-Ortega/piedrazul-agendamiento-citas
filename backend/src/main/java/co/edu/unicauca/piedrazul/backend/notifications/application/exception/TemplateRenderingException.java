package co.edu.unicauca.piedrazul.backend.notifications.application.exception;

import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationChannel;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationType;

import java.util.Locale;

/**
 * Error renderizando o resolviendo un template
 * para una notificación específica.
 */
public class TemplateRenderingException extends NotificationDispatchException {

    public TemplateRenderingException(
            NotificationType type,
            NotificationChannel channel,
            Locale locale
    ) {
        super(
                "TEMPLATE_RENDERING_ERROR",
                "No existe template para type="
                        + type
                        + ", channel="
                        + channel
                        + ", locale="
                        + locale
        );
    }

    public TemplateRenderingException(
            NotificationType type,
            NotificationChannel channel,
            String message
    ) {
        super(
                "TEMPLATE_RENDERING_ERROR",
                "Error renderizando template para type="
                        + type
                        + ", channel="
                        + channel
                        + ": "
                        + message
        );
    }
}