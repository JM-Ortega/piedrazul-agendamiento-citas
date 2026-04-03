package co.edu.unicauca.piedrazul.backend.user;

import java.util.UUID;

public interface UserModuleApi {

    UUID createPatientUser(String username, String firstName, String lastName,
                           String email, String password);

    UUID createDoctorUser(String username, String firstName, String lastName,
                          String email, String password);

    UUID createSchedulerUser(String username, String firstName, String lastName,
                             String email, String password);

    UUID createAdminUser(String username, String firstName, String lastName,
                         String email, String password);

    boolean existsById(UUID id);

    void activateUser(UUID id);

    void deactivateUser(UUID id);
}