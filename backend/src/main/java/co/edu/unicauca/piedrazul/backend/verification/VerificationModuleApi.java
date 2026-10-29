package co.edu.unicauca.piedrazul.backend.verification;

import co.edu.unicauca.piedrazul.backend.verification.api.VerificationPurpose;
import co.edu.unicauca.piedrazul.backend.verification.api.VerifiedCode;

import java.util.UUID;

public interface VerificationModuleApi {

    /**
     * Comprueba, sin consultar al sujeto ni modificar nada, que hay un canal apto para hacer
     * llegar códigos. Permite rechazar una solicitud antes de buscar a la persona, de modo que
     * el resultado no dependa de si existe.
     *
     * @throws co.edu.unicauca.piedrazul.backend.verification.exception.VerificationDeliveryUnavailableException si no hay canal apto
     */
    void requireCodeDeliveryAvailable();

    /**
     * Genera, guarda y encola un código nuevo, e invalida el activo anterior. Antes de cualquier
     * cambio comprueba el canal y los contactos: si fallan, no se toca el estado existente.
     *
     * @throws co.edu.unicauca.piedrazul.backend.verification.exception.VerificationDeliveryUnavailableException si no hay canal apto
     * @throws co.edu.unicauca.piedrazul.backend.verification.exception.VerificationContactUnusableException si los contactos no sirven para ese canal
     * @throws co.edu.unicauca.piedrazul.backend.verification.exception.VerificationCodeRequestLimitException si se superó el límite de solicitudes
     */
    void requestCode(
            String subject,
            VerificationPurpose purpose,
            String displayName,
            String phone,
            String email,
            UUID recipientId
    );

    /**
     * Verifica el código y devuelve una referencia lista para consumir.
     *
     * <p>No marca el código como usado: la referencia devuelta se consume después
     * mediante {@link #consumeCode(VerifiedCode)}.
     *
     * @throws co.edu.unicauca.piedrazul.backend.verification.exception.VerificationCodeNotFoundException si no hay código activo
     * @throws co.edu.unicauca.piedrazul.backend.verification.exception.VerificationCodeExpiredException si expiró
     * @throws co.edu.unicauca.piedrazul.backend.verification.exception.VerificationCodeBlockedException si se agotaron los intentos
     * @throws co.edu.unicauca.piedrazul.backend.verification.exception.InvalidVerificationCodeException si el código no coincide
     */
    VerifiedCode verifyCode(
            String subject,
            VerificationPurpose purpose,
            String code
    );

    /**
     * Consume, en exclusiva, un código ya verificado. Debe invocarse dentro de una
     * transacción activa; solo una ejecución concurrente puede consumirlo.
     *
     * @throws co.edu.unicauca.piedrazul.backend.verification.exception.VerificationCodeAlreadyUsedException si ya fue consumido
     */
    void consumeCode(VerifiedCode verifiedCode);
}
