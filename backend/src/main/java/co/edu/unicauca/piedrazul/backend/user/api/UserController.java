package co.edu.unicauca.piedrazul.backend.user.api;

import co.edu.unicauca.piedrazul.backend.user.api.dto.input.CreateSchedulerRequest;
import co.edu.unicauca.piedrazul.backend.user.api.dto.output.SystemUserResponse;
import co.edu.unicauca.piedrazul.backend.user.application.UserService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/user")
@PreAuthorize("hasRole('ADMIN')")
public class UserController {
    private final UserService userService;

    public UserController(UserService userService) {
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
     * Crea un nuevo usuario con rol de scheduler.
     * @param request Datos necesarios para crear el scheduler
     * @return Respuesta HTTP 204 si la operación fue exitosa
     */
    @PostMapping("/schedulers")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> createScheduler(@Valid @RequestBody CreateSchedulerRequest request) {
        userService.createScheduler(request);
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
