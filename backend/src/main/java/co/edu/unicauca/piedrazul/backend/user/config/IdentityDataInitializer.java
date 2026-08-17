package co.edu.unicauca.piedrazul.backend.user.config;

import co.edu.unicauca.piedrazul.backend.shared.enums.IdentificationType;
import co.edu.unicauca.piedrazul.backend.shared.enums.Role;
import co.edu.unicauca.piedrazul.backend.user.UserProvisioningApi;
import co.edu.unicauca.piedrazul.backend.user.api.dto.input.CreateSystemUserPayload;
import co.edu.unicauca.piedrazul.backend.user.api.dto.input.CreateSystemUserRequest;
import co.edu.unicauca.piedrazul.backend.user.exception.PersonAlreadyExistsException;
import co.edu.unicauca.piedrazul.backend.user.exception.UserAlreadyExistsException;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
@EnableConfigurationProperties(IdentitySeedProperties.class)
public class IdentityDataInitializer {

    @Bean
    ApplicationRunner seedIdentityUsers(
            UserProvisioningApi userProvisioningApi,
            IdentitySeedProperties properties
    ) {
        return args -> {
            if (!properties.isEnabled()) {
                return;
            }

            seedAdmin(userProvisioningApi, properties.getAdmin());
            seedDemoSchedulers(userProvisioningApi);
        };
    }

    private void seedAdmin(
            UserProvisioningApi userProvisioningApi,
            IdentitySeedProperties.SeedUser admin
    ) {
        require(admin.getUsername(), "IDENTITY_SEED_ADMIN_USERNAME");
        require(admin.getFirstName(), "IDENTITY_SEED_ADMIN_FIRST_NAME");
        require(admin.getLastName(), "IDENTITY_SEED_ADMIN_LAST_NAME");
        require(admin.getPhone(), "IDENTITY_SEED_ADMIN_PHONE");
        require(admin.getPassword(), "IDENTITY_SEED_ADMIN_PASSWORD");

        if (admin.getIdentificationType() == null) {
            throw new IllegalStateException(
                    "Missing required identity seed property: IDENTITY_SEED_ADMIN_IDENTIFICATION_TYPE"
            );
        }

        createIfNotExists(
                userProvisioningApi,
                new CreateSystemUserPayload(
                        new CreateSystemUserRequest(
                                admin.getUsername(),
                                admin.getIdentificationType(),
                                admin.getFirstName(),
                                admin.getLastName(),
                                admin.getEmail(),
                                admin.getPhone(),
                                admin.getPassword()
                        ),
                        null,
                        null,
                        List.of(Role.ADMIN)
                )
        );
    }

    private void seedDemoSchedulers(UserProvisioningApi userProvisioningApi) {
        String demoPassword = "Scheduler123!";

        List<DemoScheduler> schedulers = List.of(
                new DemoScheduler("9000001", "Laura", "Pérez", "laura.scheduler@piedrazul.local", "3001112233"),
                new DemoScheduler("9000002", "Carlos", "Rodríguez", "carlos.scheduler@piedrazul.local", "3001112244"),
                new DemoScheduler("9000003", "Valeria", "Torres", "valeria.scheduler@piedrazul.local", "3001112255")
        );

        schedulers.forEach(scheduler ->
                createIfNotExists(
                        userProvisioningApi,
                        new CreateSystemUserPayload(
                                new CreateSystemUserRequest(
                                        scheduler.username(),
                                        IdentificationType.CEDULA,
                                        scheduler.firstName(),
                                        scheduler.lastName(),
                                        scheduler.email(),
                                        scheduler.phone(),
                                        demoPassword
                                ),
                                null,
                                null,
                                List.of(Role.SCHEDULER)
                        )
                )
        );
    }

    private void createIfNotExists(
            UserProvisioningApi userProvisioningApi,
            CreateSystemUserPayload payload
    ) {
        try {
            userProvisioningApi.createUser(payload);
        } catch (PersonAlreadyExistsException exception) {
            // La persona puede existir si la base de datos conserva una inicialización anterior.
        } catch (UserAlreadyExistsException exception) {
            // La cuenta puede existir si Keycloak conserva una inicialización anterior.
        }
    }

    private void require(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Missing required identity seed property: " + propertyName
            );
        }
    }

    private record DemoScheduler(
            String username,
            String firstName,
            String lastName,
            String email,
            String phone
    ) {
    }
}
