package co.edu.unicauca.piedrazul.backend.notifications.domain.policy;

import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationChannel;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationType;

import java.util.List;

public class ChannelFallbackPolicy {

    public List<NotificationChannel> resolveChannels(NotificationType type) {
        return switch (type) {
            case APPOINTMENT_SCHEDULED,
                 APPOINTMENT_REMINDER_2_DAYS -> List.of(
                    NotificationChannel.WHATSAPP,
                    NotificationChannel.SMS,
                    NotificationChannel.EMAIL,
                    NotificationChannel.CONSOLE
            );

            // Solo correo (entrega real) y consola (diagnóstico, nunca entrega): no hay SMS ni
            // WhatsApp aprobados para estos avisos.
            case APPOINTMENT_CANCELLED,
                 APPOINTMENT_RESCHEDULED -> List.of(
                    NotificationChannel.EMAIL,
                    NotificationChannel.CONSOLE
            );

            case OTP_CODE -> List.of(
                    NotificationChannel.WHATSAPP,
                    NotificationChannel.SMS,
                    NotificationChannel.EMAIL,
                    NotificationChannel.CONSOLE
            );
        };
    }
}