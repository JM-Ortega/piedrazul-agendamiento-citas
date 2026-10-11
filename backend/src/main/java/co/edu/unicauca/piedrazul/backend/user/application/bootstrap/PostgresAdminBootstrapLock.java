package co.edu.unicauca.piedrazul.backend.user.application.bootstrap;

import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * Bloqueo consultivo de sesión de PostgreSQL ({@code pg_try_advisory_lock}) sobre una
 * conexión dedicada que se mantiene abierta durante el intento. No necesita tablas ni
 * privilegios adicionales, y PostgreSQL lo libera solo si el proceso muere.
 */
@Component
class PostgresAdminBootstrapLock implements AdminBootstrapLock {

    // Clave fija y exclusiva de este bootstrap dentro de la base de la aplicación
    static final long LOCK_KEY = 0x5049_4544_5241_5A01L;

    private final DataSource dataSource;

    PostgresAdminBootstrapLock(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Optional<Held> tryAcquire() {
        Connection connection;
        try {
            connection = dataSource.getConnection();
        } catch (SQLException ex) {
            throw new IllegalStateException("No se pudo obtener una conexión para el bloqueo del bootstrap", ex);
        }

        boolean acquired;
        try {
            acquired = call(connection, "SELECT pg_try_advisory_lock(?)");
        } catch (SQLException | RuntimeException ex) {
            closeQuietly(connection);
            throw new IllegalStateException("No se pudo tomar el bloqueo del bootstrap", ex);
        }

        if (!acquired) {
            closeQuietly(connection);
            return Optional.empty();
        }

        return Optional.of(() -> release(connection));
    }

    private static void release(Connection connection) {
        boolean released;
        try {
            released = call(connection, "SELECT pg_advisory_unlock(?)");
        } catch (SQLException | RuntimeException ex) {
            released = false;
        }

        if (released) {
            closeQuietly(connection);
            return;
        }

        // Sin liberar, la conexión volvería al pool con el bloqueo tomado: se descarta la
        // sesión física y PostgreSQL lo libera al cerrarla.
        try {
            connection.abort(Runnable::run);
        } catch (SQLException | RuntimeException ignored) {
            closeQuietly(connection);
        }
    }

    private static boolean call(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, LOCK_KEY);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    private static void closeQuietly(Connection connection) {
        try {
            connection.close();
        } catch (SQLException | RuntimeException ignored) {
            // la conexión ya no se usa
        }
    }
}
