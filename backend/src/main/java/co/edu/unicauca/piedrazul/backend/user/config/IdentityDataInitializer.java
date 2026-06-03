package co.edu.unicauca.piedrazul.backend.user.config;

import co.edu.unicauca.piedrazul.backend.shared.auth.Role;
import co.edu.unicauca.piedrazul.backend.user.UserProvisioningApi;
import co.edu.unicauca.piedrazul.backend.user.api.dto.input.CreateSystemUserPayload;
import co.edu.unicauca.piedrazul.backend.user.api.dto.input.CreateSystemUserRequest;
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
        require(admin.getPassword(), "IDENTITY_SEED_ADMIN_PASSWORD");

        userProvisioningApi.createUser(new CreateSystemUserPayload(
                new CreateSystemUserRequest(
                        admin.getUsername(),
                        admin.getFirstName(),
                        admin.getLastName(),
                        admin.getEmail(),
                        admin.getPassword()
                ),null, null,List.of(Role.ADMIN)
        ));
    }

        private void seedDemoSchedulers(UserProvisioningApi userProvisioningApi) {
        String demoPassword = "Scheduler123!";

        List<DemoScheduler> schedulers = List.of(
                new DemoScheduler(
                        "220000001",
                        "Carolina",
                        "Cruz",
                        "carolina.scheduler@piedrazul.com"
                ),
                new DemoScheduler(
                        "220000002",
                        "Carlos",
                        "Rodríguez",
                        "carlos.scheduler@piedrazul.com"
                )
        );

        schedulers.forEach(scheduler ->
                userProvisioningApi.createUser(new CreateSystemUserPayload(
                        new CreateSystemUserRequest(
                                scheduler.username(),
                                scheduler.firstName(),
                                scheduler.lastName(),
                                scheduler.email(),
                                demoPassword
                        ),null, null,List.of(Role.SCHEDULER)
                ))
        );
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
            String email
    ) {
    }
}