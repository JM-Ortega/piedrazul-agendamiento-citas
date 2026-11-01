package co.edu.unicauca.piedrazul.migration;

import java.sql.SQLException;
import java.sql.Statement;

import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.flywaydb.core.api.configuration.Configuration;

/**
 * Quita al rol de la aplicación todo privilegio sobre el historial de Flyway.
 *
 * <p>El historial lo crea {@code migration_role} dentro de {@code piedrazul}, así que los
 * default privileges de {@code 01-init-databases.sh} le dan DML a {@code app_role} como a
 * cualquier tabla nueva. Este callback corre al final de cada migración (también sin
 * cambios), con la conexión de Flyway, y deja el historial solo para {@code migration_role}.
 * La reconciliación de la base aplica el mismo REVOKE si el historial ya existe.
 *
 * <p>Solo lo registra {@link SchemaMigrationApplication}: en desarrollo y tests el backend
 * migra con su propio usuario y no aplica.
 */
class ProtectSchemaHistoryCallback implements Callback {

    static final String APP_ROLE_PLACEHOLDER = "app_role";

    @Override
    public boolean supports(Event event, Context context) {
        return event == Event.AFTER_MIGRATE;
    }

    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
        return true;
    }

    @Override
    public void handle(Event event, Context context) {
        Configuration configuration = context.getConfiguration();
        String sql = revokeStatement(
                configuration.getDefaultSchema(),
                configuration.getTable(),
                configuration.getPlaceholders().get(APP_ROLE_PLACEHOLDER));
        try (Statement statement = context.getConnection().createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo proteger el historial de Flyway", e);
        }
    }

    @Override
    public String getCallbackName() {
        return "protect-schema-history";
    }

    static String revokeStatement(String schema, String table, String appRole) {
        if (isBlank(schema) || isBlank(table) || isBlank(appRole)) {
            throw new IllegalStateException(
                    "Esquema, tabla de historial y placeholder app_role son obligatorios");
        }
        return "REVOKE ALL ON TABLE " + quote(schema) + "." + quote(table) + " FROM " + quote(appRole);
    }

    private static String quote(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
