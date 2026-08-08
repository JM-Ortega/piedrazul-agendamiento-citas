package co.edu.unicauca.piedrazul.backend.user.api;

import co.edu.unicauca.piedrazul.backend.shared.pagination.PageResponse;
import co.edu.unicauca.piedrazul.backend.user.api.dto.input.CreateSystemUserPayload;
import co.edu.unicauca.piedrazul.backend.user.api.dto.output.SystemDoctorResponse;
import co.edu.unicauca.piedrazul.backend.user.api.dto.output.SystemUserResponse;
import co.edu.unicauca.piedrazul.backend.user.application.CreateAccountUseCase;
import co.edu.unicauca.piedrazul.backend.user.application.UserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
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
     * <p>
     * ¿Cómo consultará esto el Frontend/Cliente?
     * </p>
     * <p>
     * Por defecto: /system-users (Page 0, Size 9, Ordenado por firstName ASC)
     * </p>
     * <p>
     * Personalizado: /system-users?page=1&size=10&sort=lastName,desc
     * </p>
     * @return Lista de usuarios registrados en el sistema
     */
    @GetMapping("/system-users")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PageResponse<SystemUserResponse>> getSystemUsers(
            @PageableDefault(page = 0, size = 9, sort = "firstName", direction = Sort.Direction.ASC) Pageable pageable
    ) {
        Page<SystemUserResponse> users = userService.getSystemUsers(pageable);
        return ResponseEntity.ok(PageResponse.from(users));
    }

    /**
     * Obtiene todos los usuarios con rol DOCTOR del sistema.
     * @return Lista de usuarios con rol DOCTOR registrados en el sistema
     */
    @GetMapping("/system-doctors")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PageResponse<SystemDoctorResponse>> getSystemDoctors(
            @PageableDefault(page = 0, size = 5, sort = "firstName", direction = Sort.Direction.ASC) Pageable pageable
    ) {
        Page<SystemDoctorResponse> doctors = userService.getSystemDoctors(pageable);
        return ResponseEntity.ok(PageResponse.from(doctors));
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
