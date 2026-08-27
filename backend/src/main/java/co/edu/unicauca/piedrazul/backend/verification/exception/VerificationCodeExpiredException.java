package co.edu.unicauca.piedrazul.backend.verification.exception;

public class VerificationCodeExpiredException extends RuntimeException {

    public VerificationCodeExpiredException() {
        super("Verification code has expired");
    }
}