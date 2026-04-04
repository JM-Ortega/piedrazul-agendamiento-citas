package co.edu.unicauca.piedrazul.backend.verification.exception;

public class VerificationCodeBlockedException extends RuntimeException {

    public VerificationCodeBlockedException() {
        super("Verification code has no attempts remaining");
    }
}