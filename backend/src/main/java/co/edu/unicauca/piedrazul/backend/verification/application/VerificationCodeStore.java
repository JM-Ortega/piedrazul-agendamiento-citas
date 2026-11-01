package co.edu.unicauca.piedrazul.backend.verification.application;

import co.edu.unicauca.piedrazul.backend.verification.api.VerificationPurpose;
import co.edu.unicauca.piedrazul.backend.verification.domain.VerificationCode;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Define las operaciones de persistencia necesarias para los códigos de
 * verificación.
 */
public interface VerificationCodeStore {

    Optional<VerificationCode> findLatestActive(String subject, VerificationPurpose purpose);

    /**
     * Igual que {@link #findLatestActive}, pero tomando un bloqueo de escritura
     * sobre la fila para serializar intentos concurrentes.
     */
    Optional<VerificationCode> findLatestActiveForUpdate(String subject, VerificationPurpose purpose);

    /**
     * Marca el código como usado únicamente si todavía no lo estaba.
     *
     * @return 1 si el código se consumió; 0 si ya estaba consumido o no existe.
     */
    int consumeIfUnused(UUID codeId);

    /**
     * Cuántos códigos se emitieron para {@code subject} y {@code purpose} desde {@code since},
     * estén usados, vencidos o activos.
     */
    long countIssuedSince(String subject, VerificationPurpose purpose, Instant since);

    VerificationCode save(VerificationCode verificationCode);
}
