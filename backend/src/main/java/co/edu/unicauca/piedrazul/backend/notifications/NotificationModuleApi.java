package co.edu.unicauca.piedrazul.backend.notifications;

import co.edu.unicauca.piedrazul.backend.notifications.domain.model.AggregateReference;
import co.edu.unicauca.piedrazul.backend.notifications.api.CancellationReason;
import co.edu.unicauca.piedrazul.backend.notifications.api.OtpDeliveryMode;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationType;
import co.edu.unicauca.piedrazul.backend.notifications.api.ScheduleNotificationCommand;
import co.edu.unicauca.piedrazul.backend.notifications.api.SendNotificationCommand;

import java.util.UUID;

public interface NotificationModuleApi {

    UUID sendNow(SendNotificationCommand command);

    UUID schedule(ScheduleNotificationCommand command);

    void cancelPendingForAggregate(
            AggregateReference aggregate,
            CancellationReason reason
    );

    boolean alreadySent(
            AggregateReference aggregate,
            NotificationType notificationType
    );

    /**
     * Capacidad actual para hacer llegar códigos de verificación; debe consultarse antes de
     * generarlos. Encolar un envío no implica que el proveedor lo acepte ni que llegue.
     */
    OtpDeliveryMode otpDeliveryMode();

    /**
     * Indica si un código para estos contactos podría llegar con la capacidad actual. Siempre es
     * {@code false} si la capacidad es {@link OtpDeliveryMode#UNAVAILABLE}.
     */
    boolean isUsableOtpRecipient(String phone, String email);
}