package co.edu.unicauca.piedrazul.backend.verification;

import co.edu.unicauca.piedrazul.backend.verification.api.VerificationPurpose;
import co.edu.unicauca.piedrazul.backend.verification.api.VerifiedCode;

import java.util.UUID;

public interface VerificationModuleApi {

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
