package co.edu.unicauca.piedrazul.backend.verification.infrastructure.delivery;

import co.edu.unicauca.piedrazul.backend.verification.application.VerificationCodeSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@ConditionalOnMissingBean(name = "notificationVerificationCodeSender")
public class ConsoleVerificationCodeSender implements VerificationCodeSender {

    private static final Logger log = LoggerFactory.getLogger(ConsoleVerificationCodeSender.class);

    @Override
    public void sendCode(String subject, String displayName, String phone, String email, String code, int expirationMinutes, UUID recipientId, UUID verificationCodeId) {
        log.info("Verification code for subject={} recipientId={} verificationCodeId={} name={} phone={} email={}: {} — expires in {} min",
                subject, recipientId, verificationCodeId, displayName, phone, email, code, expirationMinutes);
    }
}