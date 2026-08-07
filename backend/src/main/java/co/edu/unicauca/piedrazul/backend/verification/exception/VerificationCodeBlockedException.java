package co.edu.unicauca.piedrazul.backend.verification.exception;

import org.springframework.http.HttpStatus;

public class VerificationCodeBlockedException extends VerificationBusinessException {

    public VerificationCodeBlockedException() {
        super(
                "El código de verificación no tiene intentos restantes",
                "VERIFICATION_CODE_BLOCKED",
                HttpStatus.TOO_MANY_REQUESTS
        );
    }
}
