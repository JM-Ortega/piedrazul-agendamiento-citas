package co.edu.unicauca.piedrazul.backend.verification.exception;

import org.springframework.http.HttpStatus;

public class VerificationCodeAlreadyUsedException extends VerificationBusinessException {

    public VerificationCodeAlreadyUsedException() {
        super(
                "El código de verificación ya fue utilizado",
                "VERIFICATION_CODE_ALREADY_USED",
                HttpStatus.CONFLICT
        );
    }
}
