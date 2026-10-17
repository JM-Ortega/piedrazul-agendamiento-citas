package co.edu.unicauca.piedrazul.backend.statistics;

import co.edu.unicauca.piedrazul.backend.BackendApplication;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifica con Spring Modulith que statistics solo dependa de los módulos que declara. */
class StatisticsModuleStructureTest {

    private final ApplicationModules modules = ApplicationModules.of(BackendApplication.class);

    @Test
    void statisticsModuleShouldOnlyDependOnModulesItDeclares() {
        ApplicationModule statistics = modules.getModuleByName("statistics").orElseThrow();

        Set<String> dependencies = statistics.getDirectDependencies(modules).uniqueModules()
                .map(ApplicationModule::getIdentifier)
                .map(Object::toString)
                .collect(Collectors.toSet());

        assertThat(dependencies).isSubsetOf("appointment", "doctors", "shared");
    }
}
