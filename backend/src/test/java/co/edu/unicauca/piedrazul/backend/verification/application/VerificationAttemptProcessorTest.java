package co.edu.unicauca.piedrazul.backend.verification.application;

import co.edu.unicauca.piedrazul.backend.verification.api.VerificationPurpose;
import co.edu.unicauca.piedrazul.backend.verification.domain.VerificationCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VerificationAttemptProcessorTest {

    private static final String SUBJECT = "1061234567";
    private static final VerificationPurpose PURPOSE = VerificationPurpose.LINK_PATIENT_ACCOUNT;

    @Mock
    private VerificationCodeStore verificationCodeStore;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private VerificationAttemptProcessor processor;

    private VerificationCode activeCode(int maxAttempts) {
        return new VerificationCode(
                SUBJECT, PURPOSE, "hash", Instant.now().plusSeconds(300), maxAttempts);
    }

    @Test
    void shouldReportNotFoundWhenThereIsNoActiveCode() {
        when(verificationCodeStore.findLatestActiveForUpdate(SUBJECT, PURPOSE))
                .thenReturn(Optional.empty());

        VerificationAttemptResult result = processor.process(SUBJECT, PURPOSE, "123456", Instant.now());

        assertEquals(VerificationAttemptResult.Outcome.NOT_FOUND, result.outcome());
        verify(verificationCodeStore, never()).save(any());
    }

    @Test
    void shouldReportExpiredWithoutComparingTheCode() {
        VerificationCode expired = new VerificationCode(
                SUBJECT, PURPOSE, "hash", Instant.now().minusSeconds(1), 5);
        when(verificationCodeStore.findLatestActiveForUpdate(SUBJECT, PURPOSE))
                .thenReturn(Optional.of(expired));

        VerificationAttemptResult result = processor.process(SUBJECT, PURPOSE, "123456", Instant.now());

        assertEquals(VerificationAttemptResult.Outcome.EXPIRED, result.outcome());
        verify(passwordEncoder, never()).matches(anyString(), anyString());
        verify(verificationCodeStore, never()).save(any());
    }

    @Test
    void shouldReportBlockedWithoutComparingTheCodeWhenAttemptsAreExhausted() {
        VerificationCode exhausted = activeCode(1);
        exhausted.increaseAttempts();

        when(verificationCodeStore.findLatestActiveForUpdate(SUBJECT, PURPOSE))
                .thenReturn(Optional.of(exhausted));

        VerificationAttemptResult result = processor.process(SUBJECT, PURPOSE, "123456", Instant.now());

        assertEquals(VerificationAttemptResult.Outcome.BLOCKED, result.outcome());
        // Ni siquiera se compara: un código correcto tampoco puede validarse ya.
        verify(passwordEncoder, never()).matches(anyString(), anyString());
    }

    @Test
    void shouldCountOnlyIncorrectCodes() {
        VerificationCode code = activeCode(5);
        when(verificationCodeStore.findLatestActiveForUpdate(SUBJECT, PURPOSE))
                .thenReturn(Optional.of(code));
        when(passwordEncoder.matches("000000", "hash")).thenReturn(false);

        VerificationAttemptResult result = processor.process(SUBJECT, PURPOSE, "000000", Instant.now());

        assertEquals(VerificationAttemptResult.Outcome.INVALID_CODE, result.outcome());
        assertEquals(1, code.getAttempts());
        verify(verificationCodeStore).save(code);
    }

    @Test
    void shouldNotCountNorConsumeWhenCodeIsCorrect() {
        VerificationCode code = activeCode(5);
        when(verificationCodeStore.findLatestActiveForUpdate(SUBJECT, PURPOSE))
                .thenReturn(Optional.of(code));
        when(passwordEncoder.matches("123456", "hash")).thenReturn(true);

        VerificationAttemptResult result = processor.process(SUBJECT, PURPOSE, "123456", Instant.now());

        assertEquals(VerificationAttemptResult.Outcome.MATCHED, result.outcome());
        assertEquals(code.getId(), result.codeId());
        assertEquals(0, code.getAttempts());
        // El consumo ocurre después, en la transacción de quien llama.
        org.junit.jupiter.api.Assertions.assertFalse(code.isUsed());
        verify(verificationCodeStore, never()).save(any());
    }
}
