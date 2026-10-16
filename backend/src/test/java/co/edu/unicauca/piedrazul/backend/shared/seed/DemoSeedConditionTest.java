package co.edu.unicauca.piedrazul.backend.shared.seed;

import co.edu.unicauca.piedrazul.backend.doctors.config.DoctorDataInitializer;
import co.edu.unicauca.piedrazul.backend.patients.infrastructure.persistence.PatientDataInitializer;
import co.edu.unicauca.piedrazul.backend.user.config.DemoStaffDataInitializer;
import co.edu.unicauca.piedrazul.backend.user.config.IdentityDataInitializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Matriz de perfiles y opt-in: solo {@code dev} exclusivo con APP_DEMO_SEED_ENABLED=true
 * registra inicializadores de demostración.
 */
class DemoSeedConditionTest {

    @Configuration
    static class DemoBeans {
        @Bean
        @DemoSeedOnly
        String demoInitializer() {
            return "demo";
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(DemoBeans.class);

    @ParameterizedTest(name = "perfiles=''{0}'' opt-in=''{1}'' → {2}")
    @CsvSource(delimiter = '|', nullValues = "<none>", value = {
            "dev       | true  | true",
            "dev       | TRUE  | true",
            "dev       | false | false",
            "dev       | <none>| false",
            "dev       | yes   | false",
            "prod      | true  | false",
            "<none>    | true  | false",
            "staging   | true  | false",
            "dev,prod  | true  | false",
            "prod,dev  | true  | false",
            "dev,debug | true  | false",
    })
    void onlyExclusiveDevWithExplicitOptInRegistersDemoInitializers(String profiles, String optIn, boolean expected) {
        ApplicationContextRunner configured = runner;
        if (profiles != null) {
            configured = configured.withPropertyValues("spring.profiles.active=" + profiles);
        }
        if (optIn != null) {
            configured = configured.withPropertyValues(DemoSeedCondition.ENABLED_PROPERTY + "=" + optIn);
        }

        configured.run(context -> assertThat(context.containsBean("demoInitializer")).isEqualTo(expected));
    }

    @Test
    void everyDemoIdentityInitializerIsGuarded() {
        assertThat(DoctorDataInitializer.class).hasAnnotation(DemoSeedOnly.class);
        assertThat(PatientDataInitializer.class).hasAnnotation(DemoSeedOnly.class);
        assertThat(DemoStaffDataInitializer.class).hasAnnotation(DemoSeedOnly.class);
    }

    @Test
    void businessAdminBootstrapIsNotADemoInitializer() {
        assertThat(IdentityDataInitializer.class.isAnnotationPresent(DemoSeedOnly.class)).isFalse();
    }
}
