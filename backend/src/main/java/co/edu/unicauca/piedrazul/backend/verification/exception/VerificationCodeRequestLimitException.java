package co.edu.unicauca.piedrazul.backend.verification.exception;

import org.springframework.http.HttpStatus;

/**
 * Se pidieron demasiados códigos para el mismo sujeto. Sin este límite, pedir un código nuevo
 * reiniciaba los intentos (fuerza bruta) y cada solicitud enviaba un mensaje a la persona.
 */
public class VerificationCodeRequestLimitException extends VerificationBusinessException {

    public VerificationCodeRequestLimitException(String message) {
        super(
                message,
                "VERIFICATION_CODE_REQUEST_LIMIT",
                HttpStatus.TOO_MANY_REQUESTS
        );
    }
}
