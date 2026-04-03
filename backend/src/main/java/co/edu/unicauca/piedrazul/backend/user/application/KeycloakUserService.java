package co.edu.unicauca.piedrazul.backend.user.application;

import co.edu.unicauca.piedrazul.backend.shared.auth.Role;
import co.edu.unicauca.piedrazul.backend.user.UserModuleApi;
import co.edu.unicauca.piedrazul.backend.user.infrastructure.KeycloakUserClient;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class KeycloakUserService implements UserModuleApi {

    private final KeycloakUserClient keycloakClient;

    public KeycloakUserService(KeycloakUserClient keycloakClient) {
        this.keycloakClient = keycloakClient;
    }

    @Override
    public UUID createPatientUser(String username, String firstName, String lastName,
                                  String email, String password) {
        return keycloakClient.createUser(username, firstName, lastName, email, password, Role.PATIENT);
    }

    @Override
    public UUID createDoctorUser(String username, String firstName, String lastName,
                                 String email, String password) {
        return keycloakClient.createUser(username, firstName, lastName, email, password, Role.DOCTOR);
    }

    @Override
    public UUID createSchedulerUser(String username, String firstName, String lastName,
                                    String email, String password) {
        return keycloakClient.createUser(username, firstName, lastName, email, password, Role.SCHEDULER);
    }

    @Override
    public UUID createAdminUser(String username, String firstName, String lastName,
                                String email, String password) {
        return keycloakClient.createUser(username, firstName, lastName, email, password, Role.ADMIN);
    }

    @Override
    public boolean existsById(UUID id) {
        return keycloakClient.existsUser(id);
    }

    @Override
    public void activateUser(UUID id) {
        keycloakClient.activateUser(id);
    }

    @Override
    public void deactivateUser(UUID id) {
        keycloakClient.deactivateUser(id);
    }
}