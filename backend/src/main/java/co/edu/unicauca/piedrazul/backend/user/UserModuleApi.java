package co.edu.unicauca.piedrazul.backend.user;

import java.util.UUID;

public interface UserModuleApi {

    UUID createPatientUser(String username);

    UUID createDoctorUser(String username);

    UUID createSchedulerUser(String username);

    UUID createAdminUser(String username);

    boolean existsById(UUID id);

    void activateUser(UUID id);

    void deactivateUser(UUID id);
}