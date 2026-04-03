package co.edu.unicauca.piedrazul.backend.verification.exception;

public class InvalidVerificationCodeException extends RuntimeException {

    public InvalidVerificationCodeException() {
        super("Verification code is invalid");
    }
}