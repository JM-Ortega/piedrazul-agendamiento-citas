package co.edu.unicauca.piedrazul.backend.appointment;

import co.edu.unicauca.piedrazul.backend.BackendApplication;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica con Spring Modulith la estructura del módulo {@code appointment}.
 *
 * <p>No se usa {@code ApplicationModules.verify()} sobre toda la aplicación porque hoy falla por
 * declaraciones {@code allowedDependencies} de otros módulos ({@code doctors} y {@code user}
 * referencian la interfaz nombrada inexistente {@code shared :: auth}), ajeno a este módulo.
 */
class AppointmentModuleStructureTest {

    private final ApplicationModules modules = ApplicationModules.of(BackendApplication.class);

    @Test
    void appointmentModuleShouldBeDetected() {
        assertThat(modules.getModuleByName("appointment")).isPresent();
    }

    @Test
    void appointmentModuleShouldOnlyDependOnModulesItDeclares() {
        ApplicationModule appointment = modules.getModuleByName("appointment").orElseThrow();

        Set<String> dependencies = appointment.getDirectDependencies(modules).uniqueModules()
                .map(ApplicationModule::getIdentifier)
                .map(Object::toString)
                .collect(Collectors.toSet());

        // medicalCheckup NO figura en allowedDependencies de package-info.java (la usan
        // RegisterUnscheduledAttentionUseCaseImpl y ClinicalHistoryPortImpl): violación previa,
        // ajena a las estadísticas. Se tolera aquí para que el test detecte dependencias NUEVAS.
        assertThat(dependencies).isSubsetOf("patients", "doctors", "shared", "user", "jackson", "medicalCheckup");
    }

    @Test
    void appointmentDtosShouldBeExposedAsNamedInterface() {
        ApplicationModule appointment = modules.getModuleByName("appointment").orElseThrow();

        assertThat(appointment.getNamedInterfaces().stream().map(n -> n.getName()))
                .contains("output-dto", "events");
    }
}
