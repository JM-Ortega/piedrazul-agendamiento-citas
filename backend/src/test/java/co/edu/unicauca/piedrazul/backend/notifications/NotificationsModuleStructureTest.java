package co.edu.unicauca.piedrazul.backend.notifications;

import co.edu.unicauca.piedrazul.backend.BackendApplication;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;

import java.util.Set;
import java.util.stream.Collectors;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Frontera del módulo {@code notifications}. Como en los demás módulos, no se usa
 * {@code ApplicationModules.verify()}: el grafo completo tiene violaciones ajenas a este módulo.
 */
class NotificationsModuleStructureTest {

    private static final String BASE = "co.edu.unicauca.piedrazul.backend";

    private final ApplicationModules modules = ApplicationModules.of(BackendApplication.class);

    @Test
    void notificationsRespectsItsDeclaredDependencies() {
        ApplicationModule notifications = modules.getModuleByName("notifications").orElseThrow();

        notifications.detectDependencies(modules).throwIfPresent();
    }

    @Test
    void notificationsOnlyDependsOnTheModulesItDeclares() {
        ApplicationModule notifications = modules.getModuleByName("notifications").orElseThrow();

        Set<String> dependencies = notifications.getDirectDependencies(modules).uniqueModules()
                .map(ApplicationModule::getIdentifier)
                .map(Object::toString)
                .collect(Collectors.toSet());

        assertThat(dependencies).contains("appointment").isSubsetOf("appointment", "shared");
    }

    @Test
    void appointmentEligibilityIsExportedAsANamedInterface() {
        ApplicationModule appointment = modules.getModuleByName("appointment").orElseThrow();

        assertThat(appointment.getNamedInterfaces().stream().map(namedInterface -> namedInterface.getName()))
                .contains("eligibility", "events");
    }

    /** Spring Modulith admite la fachada base del módulo sin declararla; esta regla no. */
    @Test
    void notificationsSeesOnlyTheEventsAndEligibilityOfAppointment() {
        noClasses()
                .that().resideInAPackage(BASE + ".notifications..")
                .should().dependOnClassesThat(DescribedPredicate.describe(
                        "de appointment fuera de events y eligibility",
                        target -> target.getPackageName().startsWith(BASE + ".appointment")
                                && !target.getPackageName().equals(BASE + ".appointment.events")
                                && !target.getPackageName().equals(BASE + ".appointment.eligibility")))
                .check(new ClassFileImporter()
                        .withImportOption(new ImportOption.DoNotIncludeTests())
                        .importPackages(BASE + ".notifications"));
    }
}
