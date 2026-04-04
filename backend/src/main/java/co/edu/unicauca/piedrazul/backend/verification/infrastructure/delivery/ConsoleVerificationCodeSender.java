package co.edu.unicauca.piedrazul.backend.verification.infrastructure.delivery;

import co.edu.unicauca.piedrazul.backend.verification.application.VerificationCodeSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ConsoleVerificationCodeSender implements VerificationCodeSender {

    private static final Logger log = LoggerFactory.getLogger(ConsoleVerificationCodeSender.class);

    @Override
    public void sendCode(String destination, String code) {
        log.info("Verification code for {}: {}", destination, code);
    }
}