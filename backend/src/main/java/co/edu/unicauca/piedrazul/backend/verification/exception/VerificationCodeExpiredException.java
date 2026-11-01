package co.edu.unicauca.piedrazul.backend.verification.exception;

import org.springframework.http.HttpStatus;

public class VerificationCodeExpiredException extends VerificationBusinessException {

    public VerificationCodeExpiredException() {
        super(
                "El código de verificación expiró",
                "VERIFICATION_CODE_EXPIRED",
                HttpStatus.BAD_REQUEST
        );
    }
}
