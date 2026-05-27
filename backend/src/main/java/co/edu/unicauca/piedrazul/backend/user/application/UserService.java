package co.edu.unicauca.piedrazul.backend.user.application;

import co.edu.unicauca.piedrazul.backend.shared.auth.Role;
import co.edu.unicauca.piedrazul.backend.user.api.dto.internal.UserSummary;
import co.edu.unicauca.piedrazul.backend.user.api.dto.output.SystemUserResponse;
import co.edu.unicauca.piedrazul.backend.doctors.DoctorExternalService;
import co.edu.unicauca.piedrazul.backend.user.api.dto.input.CreateSchedulerRequest;
import co.edu.unicauca.piedrazul.backend.user.exception.UserAlreadyExistsException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

@Service
public class UserService {
    private final KeycloakUserService keycloakUserService;

    public UserService(
            KeycloakUserService keycloakUserService
    ) {
        this.keycloakUserService = keycloakUserService;
    }

    public void createScheduler (CreateSchedulerRequest request){
        if (keycloakUserService.findUserIdByUsername(request.documentId()).isPresent()) {
            throw new UserAlreadyExistsException(request.documentId());
        }

        keycloakUserService.getOrCreateSchedulerUser(
                request.documentId(),
                request.firstName(),
                request.lastName(),
                request.email(),
                request.password()
        );
    }

    public List<SystemUserResponse> getSystemUsers() {

        List<UserSummary> doctors = keycloakUserService.findDoctors();
        List<UserSummary> schedulers = keycloakUserService.findSchedulers();

        // Evita usuarios duplicados
        List<UserSummary> keycloakUsers = Stream.concat(
                doctors.stream(),
                schedulers.stream()
        ).distinct().toList();

        List<SystemUserResponse> result = new ArrayList<>();

        for (UserSummary user : keycloakUsers) {

            List<String> roles = keycloakUserService.getUserRoles(user.id())
                    .stream()
                    .filter(role ->
                            role.equals(Role.DOCTOR.name()) ||
                                    role.equals(Role.SCHEDULER.name())
                    )
                    .toList();

            result.add(new SystemUserResponse(
                    user.id(),
                    user.firstName(),
                    user.lastName(),
                    user.username(),
                    roles
            ));
        }

        return result;
    }
}
