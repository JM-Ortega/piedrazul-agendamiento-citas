package co.edu.unicauca.piedrazul.backend.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * PostgreSQL desechable para los tests de integración.
 *
 * <p>El contenedor se arranca una sola vez por JVM y se comparte entre clases
 * {@code *IT}; se detiene al terminar el proceso. Es independiente del PostgreSQL
 * de desarrollo del compose, que tiene volumen persistente y no daría aislamiento.
 *
 * <p>El esquema lo crean las migraciones Flyway reales, y {@code ddl-auto=validate}
 * comprueba de paso que las entidades JPA concuerdan con ellas.
 *
 * <p>Se usa {@link DynamicPropertySource} porque hace falta construir la URL con
 * {@code currentSchema=piedrazul,extensions} y apuntar Flyway al usuario del
 * contenedor; una conexión autoconfigurada no permite ese control sobre la URL.
 */
public abstract class PostgresIntegrationSupport {

    // Mismo major que la imagen de producción (infra/postgres/Dockerfile).
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void registerDataSource(DynamicPropertyRegistry registry) {
        String jdbcUrl = POSTGRES.getJdbcUrl();
        String separator = jdbcUrl.contains("?") ? "&" : "?";

        // En producción el search_path lo aporta el rol; aquí se fija en la URL
        // porque varias entidades no declaran schema explícito.
        String url = jdbcUrl + separator + "currentSchema=piedrazul,extensions";

        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        // Un único usuario: estos tests no verifican el modelo de mínimo privilegio.
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");

        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }
}
