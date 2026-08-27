package co.edu.unicauca.piedrazul.backend.verification.application;

import co.edu.unicauca.piedrazul.backend.verification.VerificationModuleApi;
import co.edu.unicauca.piedrazul.backend.verification.api.VerificationPurpose;
import co.edu.unicauca.piedrazul.backend.verification.api.VerifiedCode;
import co.edu.unicauca.piedrazul.backend.verification.domain.VerificationCode;
import co.edu.unicauca.piedrazul.backend.verification.exception.InvalidVerificationCodeException;
import co.edu.unicauca.piedrazul.backend.verification.exception.VerificationCodeAlreadyUsedException;
import co.edu.unicauca.piedrazul.backend.verification.exception.VerificationCodeBlockedException;
import co.edu.unicauca.piedrazul.backend.verification.exception.VerificationCodeExpiredException;
import co.edu.unicauca.piedrazul.backend.verification.exception.VerificationCodeNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class VerificationService implements VerificationModuleApi {

    private static final int CODE_LENGTH = 6;
    private static final int MAX_ATTEMPTS = 5;
    private static final int EXPIRATION_MINUTES = 5;

    private final VerificationCodeStore verificationCodeStore;
    private final VerificationAttemptProcessor verificationAttemptProcessor;
    private final VerificationCodeSender sender;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom secureRandom = new SecureRandom();

    public VerificationService(
            VerificationCodeStore verificationCodeStore,
            VerificationAttemptProcessor verificationAttemptProcessor,
            VerificationCodeSender sender,
            PasswordEncoder passwordEncoder
    ) {
        this.verificationCodeStore = verificationCodeStore;
        this.verificationAttemptProcessor = verificationAttemptProcessor;
        this.sender = sender;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void requestCode(String subject, VerificationPurpose purpose, String displayName, String phone, String email, UUID recipientId) {
        validateSubject(subject);
        validateAtLeastOneContact(phone, email);
        Objects.requireNonNull(recipientId, "recipientId cannot be null");

        Optional<VerificationCode> existing =
                verificationCodeStore.findLatestActive(subject, purpose);

        existing.ifPresent(code -> {
            code.invalidate();
            verificationCodeStore.save(code);
        });

        String rawCode = generateCode();
        String codeHash = passwordEncoder.encode(rawCode);

        VerificationCode verificationCode = new VerificationCode(
                subject,
                purpose,
                codeHash,
                Instant.now().plusSeconds(EXPIRATION_MINUTES * 60L),
                MAX_ATTEMPTS
        );

        verificationCodeStore.save(verificationCode);
        sender.sendCode(subject, displayName, phone, email, rawCode, EXPIRATION_MINUTES, recipientId, verificationCode.getId());
    }

    @Override
    public VerifiedCode verifyCode(String subject, VerificationPurpose purpose, String code) {
        validateSubject(subject);
        validateCode(code);

        VerificationAttemptResult result =
                verificationAttemptProcessor.process(subject, purpose, code, Instant.now());

        // Traduce el resultado después de finalizar la transacción del intento para
        // que una excepción por verificación fallida no revierta el contador de intentos.
        return switch (result.outcome()) {
            case MATCHED -> new VerifiedCodeHandle(result.codeId());
            case INVALID_CODE -> throw new InvalidVerificationCodeException();
            case EXPIRED -> throw new VerificationCodeExpiredException();
            case BLOCKED -> throw new VerificationCodeBlockedException();
            case NOT_FOUND -> throw new VerificationCodeNotFoundException(subject);
        };
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void consumeCode(VerifiedCode verifiedCode) {
        if (!(verifiedCode instanceof VerifiedCodeHandle handle)) {
            throw new IllegalArgumentException("La referencia de verificación no fue emitida por este módulo");
        }

        int consumed = verificationCodeStore.consumeIfUnused(handle.codeId());

        if (consumed == 0) {
            throw new VerificationCodeAlreadyUsedException();
        }
    }

    private String generateCode() {
        int bound = (int) Math.pow(10, CODE_LENGTH);
        int min = bound / 10;
        int value = secureRandom.nextInt(bound - min) + min;
        return String.valueOf(value);
    }

    private void validateSubject(String subject) {
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("Subject cannot be blank");
        }
    }

    private void validateAtLeastOneContact(String phone, String email) {
        boolean hasPhone = phone != null && !phone.isBlank();
        boolean hasEmail = email != null && !email.isBlank();
        if (!hasPhone && !hasEmail) {
            throw new IllegalArgumentException("At least one contact (phone or email) is required");
        }
    }

    private void validateCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Code cannot be blank");
        }
    }
}
