package co.edu.unicauca.piedrazul.backend.user.config;

import co.edu.unicauca.piedrazul.backend.user.application.bootstrap.AdminBootstrapOutcome;
import co.edu.unicauca.piedrazul.backend.user.application.bootstrap.AdminBootstrapRequest;
import co.edu.unicauca.piedrazul.backend.user.application.bootstrap.AdminBootstrapResult;
import co.edu.unicauca.piedrazul.backend.user.application.bootstrap.AdminBootstrapService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.DefaultApplicationArguments;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * El inicializador solo decide si hay bootstrap (IDENTITY_SEED_ENABLED) y entrega las
 * propiedades tal cual; la lógica la cubre AdminBootstrapServiceTest. Ya no crea
 * agendadores ni auditor (DemoStaffDataInitializer, solo con datos de demostración).
 */
class IdentityDataInitializerTest {

    private static IdentitySeedProperties properties(boolean enabled) {
        IdentitySeedProperties properties = new IdentitySeedProperties();
        properties.setEnabled(enabled);
        IdentitySeedProperties.SeedUser admin = properties.getAdmin();
        admin.setUsername("1061234567");
        admin.setIdentificationType("CEDULA");
        admin.setFirstName("Ana");
        admin.setLastName("Prueba");
        admin.setEmail("ana.prueba@example.test");
        admin.setPhone("3000000000");
        admin.setPassword("Temp-Bootstrap-Value-2026!");
        return properties;
    }

    @Test
    void disabledBootstrapPerformsNoOperation() throws Exception {
        AdminBootstrapService service = mock(AdminBootstrapService.class);

        new IdentityDataInitializer(properties(false), service).run(new DefaultApplicationArguments());

        verifyNoInteractions(service);
    }

    @Test
    void disabledBootstrapDoesNotNeedAnyInput() throws Exception {
        AdminBootstrapService service = mock(AdminBootstrapService.class);
        IdentitySeedProperties empty = new IdentitySeedProperties();

        new IdentityDataInitializer(empty, service).run(new DefaultApplicationArguments());

        verifyNoInteractions(service);
    }

    @Test
    void enabledBootstrapDeliversThePropertiesUnchanged() throws Exception {
        AdminBootstrapService service = mock(AdminBootstrapService.class);
        when(service.bootstrap(any())).thenReturn(result(AdminBootstrapOutcome.CREATED));

        new IdentityDataInitializer(properties(true), service).run(new DefaultApplicationArguments());

        ArgumentCaptor<AdminBootstrapRequest> captor = ArgumentCaptor.forClass(AdminBootstrapRequest.class);
        verify(service).bootstrap(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new AdminBootstrapRequest(
                "1061234567", "CEDULA", "Ana", "Prueba", "ana.prueba@example.test", "3000000000", "Temp-Bootstrap-Value-2026!"));
    }

    private static AdminBootstrapResult result(AdminBootstrapOutcome outcome) {
        return new AdminBootstrapResult(outcome, "attempt", "stage", List.of("reason"), null, null, Map.of());
    }

    private static IdentityDataInitializer initializerReturning(AdminBootstrapOutcome outcome) {
        AdminBootstrapService service = mock(AdminBootstrapService.class);
        when(service.bootstrap(any())).thenReturn(result(outcome));
        return new IdentityDataInitializer(properties(true), service);
    }

    @ParameterizedTest
    @EnumSource(value = AdminBootstrapOutcome.class, names = {"CREATED", "ALREADY_LINKED_READY"})
    void readyBootstrapLetsTheBackendStart(AdminBootstrapOutcome outcome) {
        assertThatCode(() -> initializerReturning(outcome).run(new DefaultApplicationArguments()))
                .doesNotThrowAnyException();
    }

    @Test
    void administrativelyAlteredLinkedIdentityStartsWithoutRepair() {
        assertThatCode(() -> initializerReturning(AdminBootstrapOutcome.ALREADY_LINKED_NOT_READY)
                .run(new DefaultApplicationArguments()))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @EnumSource(value = AdminBootstrapOutcome.class,
            names = {"CONFLICT", "INVALID_INPUT", "BUSY", "FAILED_NO_CHANGES", "FAILED_COMPENSATED", "FAILED_NEEDS_INSPECTION"})
    void unsuccessfulBootstrapAbortsStartup(AdminBootstrapOutcome outcome) {
        assertThatThrownBy(() -> initializerReturning(outcome).run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("identity-bootstrap result=" + outcome.name() + " ")
                .hasMessageNotContaining("1061234567")
                .hasMessageNotContaining("ana.prueba@example.test")
                .hasMessageNotContaining("Temp-Bootstrap-Value-2026!");
    }

    @Test
    void runsBeforeTheDemoInitializers() {
        assertThat(new IdentityDataInitializer(properties(false), mock(AdminBootstrapService.class)).getOrder()).isEqualTo(1);
    }

    @Test
    void requestNeverPrintsItsValues() {
        assertThat(new AdminBootstrapRequest("1061234567", "CEDULA", "Ana", "Prueba", "ana@example.test", "3000000000", "secreto").toString())
                .doesNotContain("1061234567", "Ana", "secreto", "ana@example.test");
    }
}
