package co.edu.unicauca.piedrazul.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

/**
 * Nivel que Spring aplica con el application.yaml real. El valor por defecto del marcador solo
 * cubre una variable ausente: una vacía deja el logger sin nivel y hereda el del root. Por eso
 * Compose nunca le entrega LOG_LEVEL_MODULITH vacío (infra/compose/backend.yml).
 */
class ModulithLogLevelConfigurationTest {

    @Configuration(proxyBeanMethods = false)
    static class NoBeans {
    }

    private static Logger modulithLogger() {
        return ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger("org.springframework.modulith");
    }

    private static Level configuredLevel(String... args) {
        SpringApplication application = new SpringApplication(NoBeans.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        try (ConfigurableApplicationContext ignored = application.run(args)) {
            return modulithLogger().getLevel();
        }
    }

    @AfterEach
    void restoreLogger() {
        modulithLogger().setLevel(null);
    }

    @Test
    void explicitValueIsApplied() {
        assertThat(configuredLevel("--LOG_LEVEL_MODULITH=ERROR")).isEqualTo(Level.ERROR);
    }

    @Test
    void absentVariableUsesWarn() {
        assertThat(configuredLevel()).isEqualTo(Level.WARN);
    }

    @Test
    void emptyVariableLeavesTheLoggerInheritingFromRoot() {
        assertThat(configuredLevel("--LOG_LEVEL_MODULITH=")).isNull();
    }
}
