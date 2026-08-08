package co.edu.unicauca.piedrazul.backend.user.api;

import co.edu.unicauca.piedrazul.backend.shared.pagination.PageResponse;
import co.edu.unicauca.piedrazul.backend.user.api.dto.input.CreateSystemUserPayload;
import co.edu.unicauca.piedrazul.backend.user.api.dto.output.SystemDoctorResponse;
import co.edu.unicauca.piedrazul.backend.user.api.dto.output.SystemUserResponse;
import co.edu.unicauca.piedrazul.backend.user.application.CreateAccountUseCase;
import co.edu.unicauca.piedrazul.backend.user.application.UserService;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/user")
@Tag(name = "Usuarios", description = "Operaciones de administración sobre usuarios del sistema")
public class UserController {
    private final CreateAccountUseCase createAccountUseCase;
    private final UserService userService;

    public UserController(CreateAccountUseCase createAccountUseCase, UserService userService) {
        this.createAccountUseCase = createAccountUseCase;
        this.userService = userService;
    }


    @GetMapping("/system-users")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Listar usuarios del sistema",
            description = "Devuelve una página con todos los usuarios registrados, ordenada y paginada según los parámetros recibidos.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Usuarios obtenidos correctamente"),
            @ApiResponse(responseCode = "401", description = "No autenticado"),
            @ApiResponse(responseCode = "403", description = "No tiene permisos para consultar usuarios")
    })
    public ResponseEntity<PageResponse<SystemUserResponse>> getSystemUsers(
            @Parameter(description = "Parámetros de paginación y ordenamiento")
            @PageableDefault(page = 0, size = 9, sort = "firstName", direction = Sort.Direction.ASC)
            Pageable pageable) {
        Page<SystemUserResponse> users = userService.getSystemUsers(pageable);
        return ResponseEntity.ok(PageResponse.from(users));
    }


    @GetMapping("/system-doctors")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Listar doctores del sistema",
            description = "Devuelve una página con los usuarios que tienen rol DOCTOR, usando paginación y ordenamiento.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Doctores obtenidos correctamente"),
            @ApiResponse(responseCode = "401", description = "No autenticado"),
            @ApiResponse(responseCode = "403", description = "No tiene permisos para consultar doctores")
    })
    public ResponseEntity<PageResponse<SystemDoctorResponse>> getSystemDoctors(
            @Parameter(description = "Parámetros de paginación y ordenamiento")
            @PageableDefault(page = 0, size = 5, sort = "firstName", direction = Sort.Direction.ASC)
            Pageable pageable) {
        Page<SystemDoctorResponse> doctors = userService.getSystemDoctors(pageable);
        return ResponseEntity.ok(PageResponse.from(doctors));
    }


    @PostMapping("/users")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Crear usuario del sistema",
            description = "Registra un nuevo usuario del sistema a partir de los datos enviados en el cuerpo de la solicitud.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Usuario creado correctamente"),
            @ApiResponse(responseCode = "400", description = "Datos de entrada inválidos"),
            @ApiResponse(responseCode = "401", description = "No autenticado"),
            @ApiResponse(responseCode = "403", description = "No tiene permisos para crear usuarios")
    })
    public ResponseEntity<Void> createUser(
            @Parameter(description = "Datos del usuario a crear")
            @Valid @RequestBody
            CreateSystemUserPayload request) {
        createAccountUseCase.execute(request);

        return ResponseEntity.noContent().build();
    }


    @PostMapping("/{document}/give-doctor-scheduler")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Asignar rol scheduler a un doctor",
            description = "Otorga el rol scheduler al usuario identificado por el número de documento recibido en la ruta.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Rol asignado correctamente"),
            @ApiResponse(responseCode = "401", description = "No autenticado"),
            @ApiResponse(responseCode = "403", description = "No tiene permisos para modificar roles")
    })
    public ResponseEntity<Void> giveScheduleRole(
            @Parameter(description = "Número de documento del doctor", example = "11000001")
            @PathVariable
            String document) {
        userService.giveDoctorScheduleRole(document);
        return ResponseEntity.noContent().build();
    }


    @DeleteMapping("/{document}/revoke-doctor-scheduler")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Revocar rol scheduler a un doctor",
            description = "Revoca el rol scheduler del usuario identificado por el número de documento recibido en la ruta.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Rol revocado correctamente"),
            @ApiResponse(responseCode = "401", description = "No autenticado"),
            @ApiResponse(responseCode = "403", description = "No tiene permisos para modificar roles")
    })
    public ResponseEntity<Void> revokeSchedulerRole(
            @Parameter(description = "Número de documento del doctor", example = "11000001")
            @PathVariable
            String document) {
        userService.revokeDoctorSchedulerRole(document);
        return ResponseEntity.noContent().build();
    }
}
