package co.edu.unicauca.piedrazul.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;

import org.flywaydb.core.api.callback.Event;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;

import co.edu.unicauca.piedrazul.backend.BackendApplication;

class SchemaMigrationApplicationTest {

    private static Map<String, String> completeEnv() {
        Map<String, String> env = new HashMap<>();
        SchemaMigrationApplication.REQUIRED_ENV.forEach(name -> env.put(name, "x"));
        return env;
    }

    @Test
    void sinVariablesRequeridasNoArrancaYTerminaConError() {
        assertThat(SchemaMigrationApplication.run(Map.of())).isEqualTo(2);
    }

    @Test
    void reportaCadaVariableAusenteOVacia() {
        Map<String, String> env = completeEnv();
        env.remove("MIGRATION_DB_PASSWORD");
        env.put("APP_DB_USERNAME", " ");

        assertThat(SchemaMigrationApplication.missingEnv(env))
                .containsExactlyInAnyOrder("MIGRATION_DB_PASSWORD", "APP_DB_USERNAME");
        assertThat(SchemaMigrationApplication.missingEnv(completeEnv())).isEmpty();
    }

    @Test
    void exigeLaAutoridadDeMigracionYNoLaContrasenaDeLaAplicacion() {
        assertThat(SchemaMigrationApplication.REQUIRED_ENV)
                .contains("MIGRATION_DB_USERNAME", "MIGRATION_DB_PASSWORD")
                .doesNotContain("APP_DB_PASSWORD");
    }

    @Test
    void fuerzaFlywayHabilitadoConSuPropiaConexion() {
        assertThat(SchemaMigrationApplication.FORCED_PROPERTIES)
                .contains("--spring.flyway.enabled=true", "--spring.flyway.url=${spring.datasource.url}");
    }

    @Test
    void soloImportaFlywayYQuedaFueraDelEscaneoDeLaAplicacion() {
        assertThat(SchemaMigrationApplication.class.getAnnotation(ImportAutoConfiguration.class).value())
                .containsExactly(FlywayAutoConfiguration.class);
        assertThat(SchemaMigrationApplication.class.getPackageName())
                .doesNotStartWith(BackendApplication.class.getPackageName());
    }

    @Test
    void registraLaProteccionDelHistorialSoloDespuesDeMigrar() {
        ProtectSchemaHistoryCallback callback = new SchemaMigrationApplication().protectSchemaHistoryCallback();

        assertThat(callback.supports(Event.AFTER_MIGRATE, null)).isTrue();
        assertThat(callback.supports(Event.BEFORE_MIGRATE, null)).isFalse();
    }

    @Test
    void revocaTodoSobreElHistorialAlRolDeLaAplicacionConIdentificadoresCitados() {
        assertThat(ProtectSchemaHistoryCallback.revokeStatement("piedrazul", "flyway_schema_history", "app\"x"))
                .isEqualTo("REVOKE ALL ON TABLE \"piedrazul\".\"flyway_schema_history\" FROM \"app\"\"x\"");
    }

    @Test
    void sinPlaceholderDelRolDeLaAplicacionFalla() {
        assertThatThrownBy(
                () -> ProtectSchemaHistoryCallback.revokeStatement("piedrazul", "flyway_schema_history", null))
                .isInstanceOf(IllegalStateException.class);
    }
}
