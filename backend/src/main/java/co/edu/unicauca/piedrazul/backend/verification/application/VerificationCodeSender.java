package co.edu.unicauca.piedrazul.backend.verification.application;

public interface VerificationCodeSender {
    void sendCode(String destination, String code);
}