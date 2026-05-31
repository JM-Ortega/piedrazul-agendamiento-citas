package co.edu.unicauca.piedrazul.backend.notifications.domain.template;

import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationChannel;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationType;

import java.util.Locale;

public interface NotificationTemplateProvider {

    TemplateDefinition findTemplate(
            NotificationType type,
            NotificationChannel channel,
            Locale locale
    );
}