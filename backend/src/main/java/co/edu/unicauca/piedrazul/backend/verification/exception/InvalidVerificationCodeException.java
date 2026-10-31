package co.edu.unicauca.piedrazul.backend.verification.exception;

import org.springframework.http.HttpStatus;

public class InvalidVerificationCodeException extends VerificationBusinessException {

    public InvalidVerificationCodeException() {
        super(
                "El código de verificación es inválido",
                "INVALID_VERIFICATION_CODE",
                HttpStatus.BAD_REQUEST
        );
    }
}
