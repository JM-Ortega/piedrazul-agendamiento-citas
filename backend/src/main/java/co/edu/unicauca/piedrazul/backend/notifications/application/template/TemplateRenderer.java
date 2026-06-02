package co.edu.unicauca.piedrazul.backend.notifications.application.template;

import co.edu.unicauca.piedrazul.backend.notifications.application.exception.TemplateRenderingException;
import co.edu.unicauca.piedrazul.backend.notifications.domain.message.*;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.Notification;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationChannel;
import co.edu.unicauca.piedrazul.backend.notifications.domain.template.NotificationTemplateProvider;
import co.edu.unicauca.piedrazul.backend.notifications.domain.template.TemplateDefinition;

import java.util.Map;

public class TemplateRenderer {

    private final NotificationTemplateProvider templateProvider;

    public TemplateRenderer(
            NotificationTemplateProvider templateProvider
    ) {
        this.templateProvider = templateProvider;
    }

    public ChannelMessage render(
            Notification notification,
            NotificationChannel channel,
            Map<String, String> variables
    ) {
        TemplateDefinition template = templateProvider.findTemplate(
                notification.getType(),
                channel,
                notification.getRecipient().locale()
        );

        return switch (channel) {
            case WHATSAPP -> renderWhatsApp(template, variables);
            case SMS -> renderSms(template, variables);
            case EMAIL -> renderEmail(template, variables);
            case CONSOLE -> renderConsole(template, variables);
        };
    }

    private WhatsAppTemplateMessage renderWhatsApp(
            TemplateDefinition template,
            Map<String, String> variables
    ) {
        if (template.providerTemplateId() == null || template.providerTemplateId().isBlank()) {
            throw new TemplateRenderingException(
                    template.type(),
                    template.channel(),
                    "El template de WhatsApp requiere providerTemplateId"
            );
        }

        return new WhatsAppTemplateMessage(
                template.providerTemplateId(),
                template.locale().getLanguage(),
                variables
        );
    }

    private SmsTextMessage renderSms(
            TemplateDefinition template,
            Map<String, String> variables
    ) {
        return new SmsTextMessage(
                interpolate(template.bodyText(), variables)
        );
    }

    private EmailMessage renderEmail(
            TemplateDefinition template,
            Map<String, String> variables
    ) {
        return new EmailMessage(
                interpolate(template.subject(), variables),
                interpolate(template.bodyHtml(), variables),
                interpolate(template.bodyText(), variables)
        );
    }

    private ConsoleLogMessage renderConsole(
            TemplateDefinition template,
            Map<String, String> variables
    ) {
        return new ConsoleLogMessage(
                interpolate(template.bodyText(), variables),
                false
        );
    }

    private String interpolate(
            String template,
            Map<String, String> variables
    ) {
        if (template == null) {
            return null;
        }

        String result = template;

        for (Map.Entry<String, String> entry : variables.entrySet()) {
            result = result.replace(
                    "{{" + entry.getKey() + "}}",
                    entry.getValue()
            );
        }

        return result;
    }
}