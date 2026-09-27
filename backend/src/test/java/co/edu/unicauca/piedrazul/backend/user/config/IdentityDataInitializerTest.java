package co.edu.unicauca.piedrazul.backend.user.config;

import co.edu.unicauca.piedrazul.backend.shared.enums.IdentificationType;
import co.edu.unicauca.piedrazul.backend.shared.enums.Role;
import co.edu.unicauca.piedrazul.backend.user.UserProvisioningApi;
import co.edu.unicauca.piedrazul.backend.user.api.dto.input.CreateSystemUserPayload;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.DefaultApplicationArguments;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * La cuenta de auditor es "quemada": sus datos van fijos en el código, no en propiedades
 * de entorno (a diferencia del admin). Solo se comprueba que el seeder los envía tal cual
 * y con el rol AUDITOR; la creación misma (idempotencia, roles inválidos) ya la cubren
 * los tests de {@code CreateAccountUseCase}.
 */
class IdentityDataInitializerTest {

    private final IdentityDataInitializer initializer = new IdentityDataInitializer();

    private static IdentitySeedProperties enabledProperties() {
        IdentitySeedProperties properties = new IdentitySeedProperties();
        properties.setEnabled(true);
        IdentitySeedProperties.SeedUser admin = properties.getAdmin();
        admin.setUsername("1000000");
        admin.setIdentificationType(IdentificationType.CEDULA);
        admin.setFirstName("Admin");
        admin.setLastName("Piedrazul");
        admin.setPhone("3000000000");
        admin.setPassword("Admin123!");
        return properties;
    }

    @Test
    void seedsAHardcodedAuditorAccountWhenSeedingIsEnabled() throws Exception {
        UserProvisioningApi userProvisioningApi = mock(UserProvisioningApi.class);

        initializer.seedIdentityUsers(userProvisioningApi, enabledProperties())
                .run(new DefaultApplicationArguments());

        ArgumentCaptor<CreateSystemUserPayload> captor = ArgumentCaptor.forClass(CreateSystemUserPayload.class);
        verify(userProvisioningApi, times(5)).createUser(captor.capture()); // admin + 3 agendadores + auditor

        CreateSystemUserPayload auditor = captor.getAllValues().stream()
                .filter(payload -> payload.roles().equals(List.of(Role.AUDITOR)))
                .findFirst().orElseThrow(() -> new AssertionError("no se envió ningún payload con rol AUDITOR"));

        assertThat(auditor.user().identification()).isEqualTo("9700001");
        assertThat(auditor.user().identificationType()).isEqualTo(IdentificationType.CEDULA);
        assertThat(auditor.user().firstName()).isNotBlank();
        assertThat(auditor.user().lastName()).isNotBlank();
        assertThat(auditor.user().phone()).matches("\\d{10}");
        assertThat(auditor.user().password()).isNotBlank();
        assertThat(auditor.doctor()).isNull();
        assertThat(auditor.patient()).isNull();
    }

    @Test
    void doesNotSeedAnythingWhenSeedingIsDisabled() throws Exception {
        UserProvisioningApi userProvisioningApi = mock(UserProvisioningApi.class);
        IdentitySeedProperties properties = new IdentitySeedProperties();
        properties.setEnabled(false);

        initializer.seedIdentityUsers(userProvisioningApi, properties).run(new DefaultApplicationArguments());

        verifyNoInteractions(userProvisioningApi);
    }
}
