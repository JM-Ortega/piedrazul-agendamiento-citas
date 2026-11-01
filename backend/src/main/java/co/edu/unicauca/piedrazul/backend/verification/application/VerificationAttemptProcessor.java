package co.edu.unicauca.piedrazul.backend.verification.application;

import co.edu.unicauca.piedrazul.backend.verification.api.VerificationPurpose;
import co.edu.unicauca.piedrazul.backend.verification.domain.VerificationCode;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Procesa un intento de verificación en una transacción independiente
 * ({@code REQUIRES_NEW}), para que el conteo de intentos fallidos persista aunque
 * quien llama termine revirtiendo.
 *
 * <p>Carga el código con bloqueo pesimista para que los intentos concurrentes
 * sobre el mismo código se serialicen.
 */
@Component
public class VerificationAttemptProcessor {

    private final VerificationCodeStore verificationCodeStore;
    private final PasswordEncoder passwordEncoder;

    public VerificationAttemptProcessor(
            VerificationCodeStore verificationCodeStore,
            PasswordEncoder passwordEncoder
    ) {
        this.verificationCodeStore = verificationCodeStore;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public VerificationAttemptResult process(
            String subject,
            VerificationPurpose purpose,
            String code,
            Instant now
    ) {
        Optional<VerificationCode> found =
                verificationCodeStore.findLatestActiveForUpdate(subject, purpose);

        if (found.isEmpty()) {
            return VerificationAttemptResult.of(VerificationAttemptResult.Outcome.NOT_FOUND);
        }

        VerificationCode verificationCode = found.get();

        if (verificationCode.isExpired(now)) {
            return VerificationAttemptResult.of(VerificationAttemptResult.Outcome.EXPIRED);
        }

        if (!verificationCode.hasAttemptsRemaining()) {
            return VerificationAttemptResult.of(VerificationAttemptResult.Outcome.BLOCKED);
        }

        if (!passwordEncoder.matches(code, verificationCode.getCodeHash())) {
            verificationCode.increaseAttempts();
            verificationCodeStore.save(verificationCode);
            return VerificationAttemptResult.of(VerificationAttemptResult.Outcome.INVALID_CODE);
        }

        return VerificationAttemptResult.matched(verificationCode.getId());
    }
}
