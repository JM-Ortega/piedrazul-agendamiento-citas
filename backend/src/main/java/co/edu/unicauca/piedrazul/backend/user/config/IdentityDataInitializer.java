package co.edu.unicauca.piedrazul.backend.user.config;

import co.edu.unicauca.piedrazul.backend.user.UserModuleApi;
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
            UserModuleApi userModuleApi,
            IdentitySeedProperties properties
    ) {
        return args -> {
            if (!properties.isEnabled()) {
                return;
            }

            seedAdmin(userModuleApi, properties.getAdmin());
            seedDemoSchedulers(userModuleApi);
        };
    }

    private void seedAdmin(
            UserModuleApi userModuleApi,
            IdentitySeedProperties.SeedUser admin
    ) {
        require(admin.getUsername(), "IDENTITY_SEED_ADMIN_USERNAME");
        require(admin.getFirstName(), "IDENTITY_SEED_ADMIN_FIRST_NAME");
        require(admin.getLastName(), "IDENTITY_SEED_ADMIN_LAST_NAME");
        require(admin.getPassword(), "IDENTITY_SEED_ADMIN_PASSWORD");

        userModuleApi.getOrCreateAdminUser(
                admin.getUsername(),
                admin.getFirstName(),
                admin.getLastName(),
                admin.getEmail(),
                admin.getPassword()
        );
    }

    private void seedDemoSchedulers(UserModuleApi userModuleApi) {
        String demoPassword = "Scheduler12345";

        List<DemoScheduler> schedulers = List.of(
                new DemoScheduler(
                        "900000001",
                        "Laura",
                        "Pérez",
                        "laura.scheduler@piedrazul.local"
                ),
                new DemoScheduler(
                        "900000002",
                        "Carlos",
                        "Rodríguez",
                        "carlos.scheduler@piedrazul.local"
                ),
                new DemoScheduler(
                        "900000003",
                        "Valeria",
                        "Torres",
                        "valeria.scheduler@piedrazul.local"
                )
        );

        schedulers.forEach(scheduler ->
                userModuleApi.getOrCreateSchedulerUser(
                        scheduler.username(),
                        scheduler.firstName(),
                        scheduler.lastName(),
                        scheduler.email(),
                        demoPassword
                )
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