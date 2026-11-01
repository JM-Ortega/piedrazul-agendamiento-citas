package co.edu.unicauca.piedrazul.backend.user.application.bootstrap;

import java.util.Optional;

/**
 * Exclusión entre intentos de bootstrap concurrentes (p. ej. dos arranques del backend
 * contra la misma base). Mientras se mantiene, ningún otro intento clasifica ni escribe.
 */
public interface AdminBootstrapLock {

    /** El bloqueo si estaba libre; vacío si otro intento lo tiene. No espera. */
    Optional<Held> tryAcquire();

    interface Held extends AutoCloseable {
        @Override
        void close();
    }
}
