package co.edu.unicauca.piedrazul.backend.verification.application;

import java.util.UUID;

public interface VerificationCodeSender {
    void sendCode(String subject, String displayName, String phone, String email, String code, int expirationMinutes, UUID recipientId, UUID verificationCodeId);
}