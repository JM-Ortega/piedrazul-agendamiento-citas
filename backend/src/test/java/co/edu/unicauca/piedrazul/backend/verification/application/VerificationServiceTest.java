package co.edu.unicauca.piedrazul.backend.verification.application;

import co.edu.unicauca.piedrazul.backend.verification.api.VerificationPurpose;
import co.edu.unicauca.piedrazul.backend.verification.api.VerifiedCode;
import co.edu.unicauca.piedrazul.backend.verification.exception.InvalidVerificationCodeException;
import co.edu.unicauca.piedrazul.backend.verification.exception.VerificationCodeAlreadyUsedException;
import co.edu.unicauca.piedrazul.backend.verification.exception.VerificationCodeBlockedException;
import co.edu.unicauca.piedrazul.backend.verification.exception.VerificationCodeExpiredException;
import co.edu.unicauca.piedrazul.backend.verification.exception.VerificationCodeNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VerificationServiceTest {

    private static final String SUBJECT = "1061234567";
    private static final VerificationPurpose PURPOSE = VerificationPurpose.LINK_PATIENT_ACCOUNT;

    @Mock
    private VerificationCodeStore verificationCodeStore;

    @Mock
    private VerificationAttemptProcessor verificationAttemptProcessor;

    @Mock
    private VerificationCodeSender sender;

    @Mock
    private PasswordEncoder passwordEncoder;

    private VerificationService service() {
        return new VerificationService(
                verificationCodeStore, verificationAttemptProcessor, sender, passwordEncoder);
    }

    private void stubOutcome(VerificationAttemptResult result) {
        when(verificationAttemptProcessor.process(eq(SUBJECT), eq(PURPOSE), anyString(), any(Instant.class)))
                .thenReturn(result);
    }

    @Test
    void shouldReturnHandleWithoutConsumingWhenCodeMatches() {
        stubOutcome(VerificationAttemptResult.matched(UUID.randomUUID()));

        VerifiedCode verified = service().verifyCode(SUBJECT, PURPOSE, "123456");

        assertNotNull(verified);
        verify(verificationCodeStore, never()).consumeIfUnused(any());
    }

    @Test
    void shouldTranslateInvalidCode() {
        stubOutcome(VerificationAttemptResult.of(VerificationAttemptResult.Outcome.INVALID_CODE));

        assertThrows(InvalidVerificationCodeException.class,
                () -> service().verifyCode(SUBJECT, PURPOSE, "000000"));
    }

    @Test
    void shouldTranslateExpiredCode() {
        stubOutcome(VerificationAttemptResult.of(VerificationAttemptResult.Outcome.EXPIRED));

        assertThrows(VerificationCodeExpiredException.class,
                () -> service().verifyCode(SUBJECT, PURPOSE, "123456"));
    }

    @Test
    void shouldTranslateBlockedCode() {
        stubOutcome(VerificationAttemptResult.of(VerificationAttemptResult.Outcome.BLOCKED));

        assertThrows(VerificationCodeBlockedException.class,
                () -> service().verifyCode(SUBJECT, PURPOSE, "123456"));
    }

    @Test
    void shouldTranslateMissingCode() {
        stubOutcome(VerificationAttemptResult.of(VerificationAttemptResult.Outcome.NOT_FOUND));

        assertThrows(VerificationCodeNotFoundException.class,
                () -> service().verifyCode(SUBJECT, PURPOSE, "123456"));
    }

    @Test
    void shouldConsumeVerifiedCodeExactlyOnce() {
        UUID codeId = UUID.randomUUID();
        stubOutcome(VerificationAttemptResult.matched(codeId));

        VerificationService service = service();
        VerifiedCode verified = service.verifyCode(SUBJECT, PURPOSE, "123456");

        when(verificationCodeStore.consumeIfUnused(codeId)).thenReturn(1);
        service.consumeCode(verified);

        verify(verificationCodeStore).consumeIfUnused(codeId);
    }

    @Test
    void shouldRejectSecondConsumptionOfTheSameCode() {
        UUID codeId = UUID.randomUUID();
        stubOutcome(VerificationAttemptResult.matched(codeId));

        VerificationService service = service();
        VerifiedCode verified = service.verifyCode(SUBJECT, PURPOSE, "123456");

        when(verificationCodeStore.consumeIfUnused(codeId)).thenReturn(0);

        assertThrows(VerificationCodeAlreadyUsedException.class, () -> service.consumeCode(verified));
    }

    @Test
    void shouldRejectHandlesNotIssuedByThisModule() {
        VerifiedCode foreign = new VerifiedCode() {
        };

        assertThrows(IllegalArgumentException.class, () -> service().consumeCode(foreign));
    }
}
