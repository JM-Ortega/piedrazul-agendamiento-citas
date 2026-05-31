package co.edu.unicauca.piedrazul.backend.user.api;

import co.edu.unicauca.piedrazul.backend.shared.auth.Role;
import co.edu.unicauca.piedrazul.backend.user.api.dto.input.CreateSystemUserPayload;
import co.edu.unicauca.piedrazul.backend.user.api.dto.output.SystemUserResponse;
import co.edu.unicauca.piedrazul.backend.user.application.CreateAccountUseCase;
import co.edu.unicauca.piedrazul.backend.user.application.UserService;
import co.edu.unicauca.piedrazul.backend.user.exception.InvalidPatientRoleAssignmentException;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/user")
public class UserController {
    private final CreateAccountUseCase createAccountUseCase;
    private final UserService userService;

    public UserController(CreateAccountUseCase createAccountUseCase, UserService userService) {
        this.createAccountUseCase = createAccountUseCase;
        this.userService = userService;
    }

    /**
     * Obtiene todos los usuarios del sistema.
     * @return Lista de usuarios registrados en el sistema
     */
    @GetMapping("/system-users")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<SystemUserResponse>> getSystemUsers() {
        List<SystemUserResponse> users = userService.getSystemUsers();
        return ResponseEntity.ok(users);
    }

    /**
     * Crea un nuevo usuario del sistema.
     * @param request Datos del usuario a crear
     * @return Respuesta HTTP 204 si la operación fue exitosa
     */
    @PostMapping("/users")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> createUser(
            @Valid @RequestBody CreateSystemUserPayload request
    ) {
        createAccountUseCase.execute(request);

        return ResponseEntity.noContent().build();
    }

    /**
     * Endpoind público para crear un nuevo paciente en el sistema.
     * @param request Datos del usuario a crear
     * @return Respuesta HTTP 204 si la operación fue exitosa
     */
    @PostMapping("/patient-user")
    public ResponseEntity<Void> createPatientUser(
            @Valid @RequestBody CreateSystemUserPayload request
    ) {
        if (request.roles() == null || request.roles().size() != 1 || !request.roles().contains(Role.PATIENT)){
            throw new InvalidPatientRoleAssignmentException(
                    "Un paciente solo puede tener asignado el rol PATIENT y ningún otro privilegio adicional."
            );
        }

        createAccountUseCase.execute(request);

        return ResponseEntity.noContent().build();
    }

    /**
     * Asigna el rol scheduler a un doctor.
     * @param username Identificación del doctor al que se le asignará el rol
     * @return Respuesta HTTP 204 si la operación fue exitosa
     */
    @PostMapping("/{username}/give-doctor-scheduler")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> giveScheduleRole(@PathVariable String username) {
        userService.giveDoctorScheduleRole(username);
        return ResponseEntity.noContent().build();
    }

    /**
     * Revoca el rol scheduler de un doctor.
     * @param username Identificación de doctor al que se le removerá el rol
     * @return Respuesta HTTP 204 si la operación fue exitosa
     */
    @DeleteMapping("/{username}/revoke-doctor-scheduler")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> revokeSchedulerRole(@PathVariable String username) {
        userService.revokeDoctorSchedulerRole(username);
        return ResponseEntity.noContent().build();
    }
}
