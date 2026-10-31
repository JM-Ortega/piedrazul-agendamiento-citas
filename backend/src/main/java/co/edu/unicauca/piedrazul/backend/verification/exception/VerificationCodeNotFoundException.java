package co.edu.unicauca.piedrazul.backend.verification.exception;

import org.springframework.http.HttpStatus;

public class VerificationCodeNotFoundException extends VerificationBusinessException {

    public VerificationCodeNotFoundException(String subject) {
        super(
                "No se encontró un código de verificación para: " + subject,
                "VERIFICATION_CODE_NOT_FOUND",
                HttpStatus.NOT_FOUND
        );
    }
}
