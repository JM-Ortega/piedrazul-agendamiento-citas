package co.edu.unicauca.piedrazul.backend.verification.api;

/**
 * Referencia opaca emitida tras verificar un código con éxito, todavía sin
 * consumir.
 *
 * <p>No expone su identificador interno. {@code consumeCode} solo acepta
 * referencias válidas, emitidas por este módulo mediante {@code verifyCode}.
 */
public interface VerifiedCode {
}
