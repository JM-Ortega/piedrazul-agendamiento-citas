package co.edu.unicauca.piedrazul.backend.user.config;

import co.edu.unicauca.piedrazul.backend.shared.enums.IdentificationType;
import co.edu.unicauca.piedrazul.backend.shared.enums.Role;
import co.edu.unicauca.piedrazul.backend.shared.seed.DemoSeedOnly;
import co.edu.unicauca.piedrazul.backend.user.UserProvisioningApi;
import co.edu.unicauca.piedrazul.backend.user.api.dto.input.CreateSystemUserPayload;
import co.edu.unicauca.piedrazul.backend.user.api.dto.input.CreateSystemUserRequest;
import co.edu.unicauca.piedrazul.backend.user.exception.PersonAlreadyExistsException;
import co.edu.unicauca.piedrazul.backend.user.exception.UserAlreadyExistsException;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Personal de demostración (agendadores y auditor) para desarrollo. Solo existe con
 * {@link DemoSeedOnly}: perfil {@code dev} exclusivo y {@code APP_DEMO_SEED_ENABLED=true}.
 * Datos ficticios (dominio reservado {@code .test}); contraseñas conocidas, solo para
 * desarrollo.
 */
@Component
@DemoSeedOnly
@Order(4)
public class DemoStaffDataInitializer implements ApplicationRunner {

    static final String DEMO_SCHEDULER_PASSWORD = "Scheduler123!";
    static final String DEMO_AUDITOR_PASSWORD = "Auditor123!";

    private final UserProvisioningApi userProvisioningApi;

    public DemoStaffDataInitializer(UserProvisioningApi userProvisioningApi) {
        this.userProvisioningApi = userProvisioningApi;
    }

    @Override
    public void run(ApplicationArguments args) {
        List.of(
                new DemoStaff("9000001", "Lucía", "Demo Agenda", "lucia.agenda@demo.piedrazul.test", "3000000401"),
                new DemoStaff("9000002", "Mateo", "Demo Agenda", "mateo.agenda@demo.piedrazul.test", "3000000402"),
                new DemoStaff("9000003", "Noa", "Demo Agenda", "noa.agenda@demo.piedrazul.test", "3000000403")
        ).forEach(scheduler -> createIfNotExists(scheduler, DEMO_SCHEDULER_PASSWORD, Role.SCHEDULER));

        createIfNotExists(
                new DemoStaff("9700001", "Olivia", "Demo Auditoría", "olivia.auditoria@demo.piedrazul.test", "3000000501"),
                DEMO_AUDITOR_PASSWORD,
                Role.AUDITOR);
    }

    private void createIfNotExists(DemoStaff staff, String password, Role role) {
        try {
            userProvisioningApi.createUser(new CreateSystemUserPayload(
                    new CreateSystemUserRequest(
                            staff.identification(),
                            IdentificationType.CEDULA,
                            staff.firstName(),
                            staff.lastName(),
                            staff.email(),
                            staff.phone(),
                            password
                    ),
                    null,
                    null,
                    List.of(role)
            ));
        } catch (PersonAlreadyExistsException exception) {
            // La persona puede existir si la base de datos conserva una inicialización anterior.
        } catch (UserAlreadyExistsException exception) {
            // La cuenta puede existir si Keycloak conserva una inicialización anterior.
        }
    }

    private record DemoStaff(String identification, String firstName, String lastName, String email, String phone) {
    }
}
