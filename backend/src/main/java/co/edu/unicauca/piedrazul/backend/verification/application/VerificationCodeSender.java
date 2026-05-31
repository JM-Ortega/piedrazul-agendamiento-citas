package co.edu.unicauca.piedrazul.backend.verification.application;

public interface VerificationCodeSender {
    void sendCode(String subject, String displayName, String phone, String email, String code, int expirationMinutes);
}