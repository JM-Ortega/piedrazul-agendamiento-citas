package co.edu.unicauca.piedrazul.backend.verification.application;

import java.util.UUID;

/**
 * Resultado de un intento de verificación. El identificador solo viene poblado
 * cuando el resultado es {@link Outcome#MATCHED}.
 */
public record VerificationAttemptResult(Outcome outcome, UUID codeId) {

    public enum Outcome {
        MATCHED,
        INVALID_CODE,
        EXPIRED,
        BLOCKED,
        NOT_FOUND
    }

    public static VerificationAttemptResult matched(UUID codeId) {
        return new VerificationAttemptResult(Outcome.MATCHED, codeId);
    }

    public static VerificationAttemptResult of(Outcome outcome) {
        return new VerificationAttemptResult(outcome, null);
    }
}
