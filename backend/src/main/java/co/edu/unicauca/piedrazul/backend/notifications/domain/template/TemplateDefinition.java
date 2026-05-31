package co.edu.unicauca.piedrazul.backend.notifications.domain.template;

import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationChannel;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationType;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

public record TemplateDefinition(
        NotificationType type,
        NotificationChannel channel,
        Locale locale,
        String templateKey,
        String subject,
        String bodyText,
        String bodyHtml,
        String providerTemplateId,
        List<String> whatsappVariableOrder  // nullable, solo para WhatsApp
) {
    public TemplateDefinition {
        Objects.requireNonNull(type, "El tipo de notificación es obligatorio");
        Objects.requireNonNull(channel, "El canal es obligatorio");
        Objects.requireNonNull(locale, "El locale es obligatorio");
        Objects.requireNonNull(templateKey, "La clave del template es obligatoria");
    }
}