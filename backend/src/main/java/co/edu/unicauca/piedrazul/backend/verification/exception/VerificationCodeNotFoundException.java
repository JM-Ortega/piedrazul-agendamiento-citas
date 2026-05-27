package co.edu.unicauca.piedrazul.backend.verification.exception;

public class VerificationCodeNotFoundException extends RuntimeException {

    public VerificationCodeNotFoundException(String subject) {
        super("Verification code not found for subject: " + subject);
    }
}