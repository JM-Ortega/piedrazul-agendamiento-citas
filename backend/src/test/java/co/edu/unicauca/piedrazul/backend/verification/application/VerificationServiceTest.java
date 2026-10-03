package co.edu.unicauca.piedrazul.backend.verification.application;

import co.edu.unicauca.piedrazul.backend.verification.api.VerificationPurpose;
import co.edu.unicauca.piedrazul.backend.verification.api.VerifiedCode;
import co.edu.unicauca.piedrazul.backend.verification.domain.VerificationCode;
import co.edu.unicauca.piedrazul.backend.verification.exception.InvalidVerificationCodeException;
import co.edu.unicauca.piedrazul.backend.verification.exception.VerificationCodeAlreadyUsedException;
import co.edu.unicauca.piedrazul.backend.verification.exception.VerificationCodeBlockedException;
import co.edu.unicauca.piedrazul.backend.verification.exception.VerificationCodeExpiredException;
import co.edu.unicauca.piedrazul.backend.verification.exception.VerificationCodeNotFoundException;
import co.edu.unicauca.piedrazul.backend.verification.exception.VerificationCodeRequestLimitException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
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

    // ---- límite de solicitudes de código ----

    private static final String PHONE = "3001234567";
    private static final String EMAIL = "ana@correo.com";
    private static final UUID RECIPIENT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    /**
     * Simula cuántos códigos se emitieron en el último minuto y en la última hora. Solo responde
     * a esas dos ventanas: si el servicio consultara otras, vería cero y no limitaría nada.
     */
    private void stubIssued(long lastMinute, long lastHour) {
        lenient().when(verificationCodeStore.countIssuedSince(eq(SUBJECT), eq(PURPOSE),
                        argThat(since -> isAbout(since, Duration.ofMinutes(1)))))
                .thenReturn(lastMinute);
        lenient().when(verificationCodeStore.countIssuedSince(eq(SUBJECT), eq(PURPOSE),
                        argThat(since -> isAbout(since, Duration.ofHours(1)))))
                .thenReturn(lastHour);
    }

    private static boolean isAbout(Instant since, Duration ago) {
        Instant expected = Instant.now().minus(ago);
        return since != null && Duration.between(since, expected).abs().compareTo(Duration.ofSeconds(5)) < 0;
    }

    private void requestCode() {
        service().requestCode(SUBJECT, PURPOSE, "Ana Ruiz", PHONE, EMAIL, RECIPIENT);
    }

    @Test
    void shouldIssueAndSendACodeWhenUnderTheLimits() {
        stubIssued(0, 4);
        when(passwordEncoder.encode(anyString())).thenReturn("hash");

        requestCode();

        verify(verificationCodeStore).save(any(VerificationCode.class));
        verify(sender).sendCode(eq(SUBJECT), anyString(), eq(PHONE), eq(EMAIL), anyString(), anyInt(), eq(RECIPIENT), any());
    }

    @Test
    void shouldRejectANewCodeRequestedWithinAMinuteOfThePreviousOne() {
        stubIssued(1, 1);

        assertThrows(VerificationCodeRequestLimitException.class, this::requestCode);

        verifyNothingIssuedNorSent();
    }

    @Test
    void shouldRejectTheSixthCodeRequestedWithinAnHour() {
        stubIssued(0, 5);

        assertThrows(VerificationCodeRequestLimitException.class, this::requestCode);

        verifyNothingIssuedNorSent();
    }

    @Test
    void shouldKeepTheActiveCodeUsableWhenARequestIsRejected() {
        stubIssued(1, 1);
        VerificationCode active = new VerificationCode(SUBJECT, PURPOSE, "hash",
                Instant.now().plus(Duration.ofMinutes(5)), 5);
        lenient().when(verificationCodeStore.findLatestActiveForUpdate(SUBJECT, PURPOSE)).thenReturn(Optional.of(active));
        lenient().when(verificationCodeStore.findLatestActive(SUBJECT, PURPOSE)).thenReturn(Optional.of(active));

        assertThrows(VerificationCodeRequestLimitException.class, this::requestCode);

        assertTrue(active.isUsable(Instant.now()), "una solicitud rechazada no debe invalidar el código vigente");
    }

    private void verifyNothingIssuedNorSent() {
        verify(verificationCodeStore, never()).save(any());
        verify(sender, never()).sendCode(any(), any(), any(), any(), any(), anyInt(), any(), any());
    }
}
