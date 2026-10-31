package co.edu.unicauca.piedrazul.migration;

import java.util.List;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

/**
 * Migración de esquema de un solo uso (producción).
 *
 * <p>Corre desde la misma imagen del backend, con otro punto de entrada
 * ({@code loader.main} del {@code PropertiesLauncher} de Spring Boot; ver
 * {@code infra/compose/migrate.yml}). Aplica las migraciones Flyway que trae la imagen con
 * la autoridad de migraciones ({@code MIGRATION_DB_*}) y termina: 0 si Flyway terminó bien,
 * distinto de 0 si no.
 *
 * <p>Está fuera del paquete {@code co.edu.unicauca.piedrazul.backend} a propósito: no
 * escanea componentes ni activa la autoconfiguración completa. Solo importa Flyway, así que
 * no hay servidor web, JPA, seeders, schedulers, Keycloak ni eventos de Modulith. La
 * configuración de Flyway es la misma de {@code application.yaml} ({@code spring.flyway.*});
 * aquí solo se fuerza que esté habilitado y que use su propia conexión.
 *
 * <p>Al terminar cada migración, {@link ProtectSchemaHistoryCallback} deja el historial de
 * Flyway fuera del alcance del rol de la aplicación.
 */
@SpringBootConfiguration(proxyBeanMethods = false)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
public class SchemaMigrationApplication {

    /**
     * Entrada obligatoria: sin estas variables {@code application.yaml} caería en los valores
     * por defecto de desarrollo. {@code APP_DB_USERNAME} solo alimenta el placeholder
     * {@code app_role} de las migraciones; la contraseña de la aplicación no se usa.
     */
    static final List<String> REQUIRED_ENV = List.of(
            "DB_HOST", "DB_PORT", "DB_NAME", "DB_SCHEMA",
            "MIGRATION_DB_USERNAME", "MIGRATION_DB_PASSWORD",
            "APP_DB_USERNAME");

    /** Precedencia de línea de comandos: ninguna variable de entorno los desactiva. */
    static final String[] FORCED_PROPERTIES = {
            "--spring.flyway.enabled=true",
            "--spring.flyway.url=${spring.datasource.url}",
            "--spring.main.banner-mode=off",
    };

    @Bean
    ProtectSchemaHistoryCallback protectSchemaHistoryCallback() {
        return new ProtectSchemaHistoryCallback();
    }

    public static void main(String[] args) {
        System.exit(run(System.getenv()));
    }

    static int run(Map<String, String> env) {
        List<String> missing = missingEnv(env);
        if (!missing.isEmpty()) {
            System.err.println("MIGRACIÓN: faltan variables requeridas: " + String.join(", ", missing));
            return 2;
        }

        SpringApplication application = new SpringApplication(SchemaMigrationApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setRegisterShutdownHook(false);
        try (ConfigurableApplicationContext context = application.run(FORCED_PROPERTIES)) {
            // FlywayMigrationInitializer migra al refrescar el contexto
            System.out.println("MIGRACIÓN: esquema al día");
            return 0;
        } catch (RuntimeException e) {
            System.err.println("MIGRACIÓN: falló — " + e.getMessage());
            return 1;
        }
    }

    static List<String> missingEnv(Map<String, String> env) {
        return REQUIRED_ENV.stream()
                .filter(name -> env.get(name) == null || env.get(name).isBlank())
                .toList();
    }
}
