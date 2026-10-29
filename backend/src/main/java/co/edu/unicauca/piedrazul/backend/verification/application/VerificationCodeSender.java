package co.edu.unicauca.piedrazul.backend.verification.application;

import java.util.UUID;

public interface VerificationCodeSender {

    /**
     * Indica si hay un canal apto para hacer llegar códigos (incluido el diagnóstico por consola
     * autorizado). Se consulta antes de generar o guardar un código.
     */
    boolean isDeliveryAvailable();

    /**
     * Indica si un código para estos contactos podría llegar por el canal disponible. Se consulta
     * con los mismos valores que luego recibe {@link #sendCode}.
     */
    boolean isRecipientUsable(String phone, String email);

    /** Encola el envío; no confirma que el proveedor lo acepte ni que llegue al destinatario. */
    void sendCode(String subject, String displayName, String phone, String email, String code, int expirationMinutes, UUID recipientId, UUID verificationCodeId);
}
