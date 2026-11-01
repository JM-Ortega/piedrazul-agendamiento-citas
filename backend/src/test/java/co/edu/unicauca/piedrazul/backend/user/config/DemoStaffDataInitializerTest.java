package co.edu.unicauca.piedrazul.backend.user.config;

import co.edu.unicauca.piedrazul.backend.shared.enums.IdentificationType;
import co.edu.unicauca.piedrazul.backend.shared.enums.Role;
import co.edu.unicauca.piedrazul.backend.user.UserProvisioningApi;
import co.edu.unicauca.piedrazul.backend.user.api.dto.input.CreateSystemUserPayload;
import co.edu.unicauca.piedrazul.backend.user.exception.PersonAlreadyExistsException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.DefaultApplicationArguments;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Agendadores y auditor de demostración: mismos documentos y roles que antes (supuestos
 * de desarrollo), con datos ficticios en el dominio reservado .test.
 */
class DemoStaffDataInitializerTest {

    @Test
    void seedsFictionalSchedulersAndAuditor() throws Exception {
        UserProvisioningApi api = mock(UserProvisioningApi.class);

        new DemoStaffDataInitializer(api).run(new DefaultApplicationArguments());

        ArgumentCaptor<CreateSystemUserPayload> captor = ArgumentCaptor.forClass(CreateSystemUserPayload.class);
        verify(api, times(4)).createUser(captor.capture());
        List<CreateSystemUserPayload> payloads = captor.getAllValues();

        assertThat(payloads).extracting(p -> p.user().identification())
                .containsExactly("9000001", "9000002", "9000003", "9700001");
        assertThat(payloads).extracting(CreateSystemUserPayload::roles)
                .containsExactly(List.of(Role.SCHEDULER), List.of(Role.SCHEDULER), List.of(Role.SCHEDULER), List.of(Role.AUDITOR));
        assertThat(payloads).allSatisfy(p -> {
            assertThat(p.user().identificationType()).isEqualTo(IdentificationType.CEDULA);
            assertThat(p.user().email()).endsWith("@demo.piedrazul.test");
            assertThat(p.user().lastName()).startsWith("Demo");
            assertThat(p.user().phone()).matches("\\d{10}");
            assertThat(p.roles()).doesNotContain(Role.ADMIN);
        });
    }

    @Test
    void toleratesIdentitiesKeptFromAPreviousRun() throws Exception {
        UserProvisioningApi api = mock(UserProvisioningApi.class);
        doThrow(new PersonAlreadyExistsException("existe")).when(api).createUser(any());

        new DemoStaffDataInitializer(api).run(new DefaultApplicationArguments());

        verify(api, times(4)).createUser(any());
    }
}
