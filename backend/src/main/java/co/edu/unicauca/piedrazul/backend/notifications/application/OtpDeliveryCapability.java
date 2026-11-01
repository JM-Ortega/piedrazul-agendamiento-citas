package co.edu.unicauca.piedrazul.backend.notifications.application;

import co.edu.unicauca.piedrazul.backend.notifications.api.OtpDeliveryMode;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationChannel;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationType;
import co.edu.unicauca.piedrazul.backend.notifications.domain.policy.ChannelFallbackPolicy;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Decide si se puede emitir un código de verificación y para qué destinatarios, antes de
 * generarlo. Los proveedores no cambian en ejecución, así que el modo se resuelve una vez.
 *
 * <ul>
 *   <li>{@link OtpDeliveryMode#EMAIL}: hay un proveedor de correo apto para códigos y ningún otro
 *       proveedor real no apto que pudiera recibirlos antes.</li>
 *   <li>{@link OtpDeliveryMode#DIAGNOSTIC_CONSOLE}: no hay ningún proveedor real y el opt-in de
 *       diagnóstico está activo (las mismas condiciones con las que el despachador deja llegar
 *       contenido sensible a la consola).</li>
 *   <li>{@link OtpDeliveryMode#UNAVAILABLE}: cualquier otro caso, incluido un proveedor real no
 *       apto (que además impide el diagnóstico por consola).</li>
 * </ul>
 */
public class OtpDeliveryCapability {

    private static final Logger log = LoggerFactory.getLogger(OtpDeliveryCapability.class);

    private final OtpDeliveryMode mode;
    private final RecipientEmailPolicy emailPolicy;

    public OtpDeliveryCapability(
            ProviderRegistry providerRegistry,
            ChannelFallbackPolicy fallbackPolicy,
            boolean consoleSensitiveDiagnosticEnabled,
            Validator validator
    ) {
        this.emailPolicy = new RecipientEmailPolicy(validator);
        this.mode = resolveMode(providerRegistry, fallbackPolicy, consoleSensitiveDiagnosticEnabled);
        logMode();
    }

    public OtpDeliveryMode mode() {
        return mode;
    }

    /**
     * Indica si el destinatario puede recibir un código con el modo actual. Con correo, se valida
     * el valor exacto que se encolaría; el teléfono no cuenta mientras no haya SMS ni WhatsApp.
     */
    public boolean isUsableRecipient(String phone, String email) {
        return switch (mode()) {
            case UNAVAILABLE -> false;
            // La consola no entrega nada al destinatario; basta con que exista algún contacto.
            case DIAGNOSTIC_CONSOLE -> !isBlank(phone) || !isBlank(email);
            case EMAIL -> emailPolicy.isUsable(email);
        };
    }

    private static OtpDeliveryMode resolveMode(
            ProviderRegistry providerRegistry,
            ChannelFallbackPolicy fallbackPolicy,
            boolean consoleSensitiveDiagnosticEnabled
    ) {
        List<NotificationChannel> otpChannels = fallbackPolicy.resolveChannels(NotificationType.OTP_CODE);

        if (!providerRegistry.hasDeliveryProvider()) {
            boolean consoleUsable = consoleSensitiveDiagnosticEnabled
                    && otpChannels.contains(NotificationChannel.CONSOLE)
                    && providerRegistry.hasProvider(NotificationChannel.CONSOLE);

            return consoleUsable ? OtpDeliveryMode.DIAGNOSTIC_CONSOLE : OtpDeliveryMode.UNAVAILABLE;
        }

        for (NotificationChannel channel : NotificationChannel.values()) {
            if (channel != NotificationChannel.CONSOLE
                    && providerRegistry.hasProvider(channel)
                    && !providerRegistry.getProvider(channel).qualifiesForOtpDelivery()) {
                return OtpDeliveryMode.UNAVAILABLE;
            }
        }

        boolean emailUsable = otpChannels.contains(NotificationChannel.EMAIL)
                && providerRegistry.hasProvider(NotificationChannel.EMAIL);

        return emailUsable ? OtpDeliveryMode.EMAIL : OtpDeliveryMode.UNAVAILABLE;
    }

    private void logMode() {
        switch (mode) {
            case UNAVAILABLE -> log.warn(
                    "[OTP_DELIVERY] Sin canal apto para códigos de verificación: las solicitudes de código se rechazarán (503)");
            case DIAGNOSTIC_CONSOLE -> log.warn(
                    "[OTP_DELIVERY] Diagnóstico por consola: los códigos no se entregan al destinatario; solo datos sintéticos");
            case EMAIL -> log.info("[OTP_DELIVERY] Códigos de verificación por correo");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
