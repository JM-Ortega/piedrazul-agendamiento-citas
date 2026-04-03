package co.edu.unicauca.piedrazul.backend.user;

import java.util.UUID;

public interface UserModuleApi {

    UUID getOrCreatePatientUser(String username, String firstName, String lastName,
                                String email, String password);

    UUID getOrCreateDoctorUser(String username, String firstName, String lastName,
                               String email, String password);

    UUID getOrCreateSchedulerUser(String username, String firstName, String lastName,
                                  String email, String password);

    UUID getOrCreateAdminUser(String username, String firstName, String lastName,
                              String email, String password);

    boolean existsById(UUID id);

    void activateUser(UUID id);

    void deactivateUser(UUID id);
}