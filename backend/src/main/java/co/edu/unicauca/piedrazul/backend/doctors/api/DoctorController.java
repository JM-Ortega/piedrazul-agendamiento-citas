package co.edu.unicauca.piedrazul.backend.doctors.api;

import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.output.DoctorDetailedResponse;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.output.DoctorResponse;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.output.DoctorShortResponse;
import co.edu.unicauca.piedrazul.backend.doctors.application.DoctorService;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Doctor;
import co.edu.unicauca.piedrazul.backend.shared.audit.SecurityContextExtractor;
import co.edu.unicauca.piedrazul.backend.shared.enums.SpecialtyCode;
import co.edu.unicauca.piedrazul.backend.shared.pagination.PageResponse;
import co.edu.unicauca.piedrazul.backend.user.PersonExternalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Tag(name = "Doctores", description = "Operaciones de consulta y administración de doctores")
@RestController
@RequestMapping("/api/doctor")
public class DoctorController {
    private final DoctorService doctorService;
    private final PersonExternalService personExternalService;

    public DoctorController(DoctorService doctorService, PersonExternalService personExternalService,
                            SecurityContextExtractor securityContextExtractor) {
        this.doctorService = doctorService;
        this.personExternalService = personExternalService;
    }

    @GetMapping("/me")
    @PreAuthorize("hasRole('DOCTOR')")
    @Operation(summary = "Obtener el doctor autenticado",
            description = "Devuelve la información detallada del doctor asociado al usuario autenticado, identificado a partir del token JWT.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Doctor obtenido correctamente"),
            @ApiResponse(responseCode = "401", description = "No autenticado"),
            @ApiResponse(responseCode = "403", description = "No tiene permisos para consultar el recurso"),
            @ApiResponse(responseCode = "404", description = "El usuario autenticado no tiene un doctor asociado")
    })
    public DoctorDetailedResponse findMe(@AuthenticationPrincipal Jwt jwt) {
        Doctor doctor = doctorService.findByUserId(UUID.fromString(jwt.getSubject()));

        Map<UUID, String> names = personExternalService.getPersonNames(List.of(doctor.getPersonId()));

        return DoctorDetailedResponse.fromEntity(doctor, names.get(doctor.getPersonId()));
    }

    // No paginar
    @GetMapping("/neural-doctors")
    @PreAuthorize("hasAnyRole('SCHEDULER', 'PATIENT', 'DOCTOR')")
    @Operation(summary = "Listar todos los doctores",
            description = "Devuelve la lista completa de doctores con especialidad de terapia neural registrados en el sistema, " +
                    "incluyendo sus especialidades y nombre. La lista puede estar vacía si no hay doctores para terapia neural registrados.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Doctores obtenidos correctamente"),
            @ApiResponse(responseCode = "401", description = "No autenticado"),
            @ApiResponse(responseCode = "403", description = "No tiene permisos para consultar doctores")
    })
    public ResponseEntity<List<DoctorShortResponse>> getNeuralDoctors() {
        return ResponseEntity.ok(doctorService.getNeuralDoctors());
    }

    // No paginar
    @GetMapping("/active-doctors")
    @PreAuthorize("hasAnyRole('SCHEDULER', 'PATIENT', 'DOCTOR')")
    @Operation(summary = "Listar todos los doctores",
            description = "Devuelve la lista completa de doctores registrados en el sistema junto con su " +
                    "información detallada. La lista puede estar vacía si no hay doctores registrados. Si el paciente es " +
                    "nuevo, solo envia medicos con la especialdiad de terapia neural y filtra las especialidades del " +
                    "medico para que solo devuelva la de terapia neural")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Doctores obtenidos correctamente"),
            @ApiResponse(responseCode = "401", description = "No autenticado"),
            @ApiResponse(responseCode = "403", description = "No tiene permisos para consultar doctores")
    })
    public ResponseEntity<List<DoctorResponse>> getActiveDoctors(UUID patientId) {
        return ResponseEntity.ok(doctorService.getActiveDoctors(patientId));
    }

    // No paginar sirve para filtrar las citas por doctor
    @GetMapping
    @PreAuthorize("hasRole('SCHEDULER')")
    @Operation(summary = "Listar todos los doctores",
            description = "Devuelve la lista completa de doctores registrados en el sistema, incluyendo sus " +
                    "especialidades y nombre. La lista puede estar vacía si no hay doctores registrados.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Doctores obtenidos correctamente"),
            @ApiResponse(responseCode = "401", description = "No autenticado"),
            @ApiResponse(responseCode = "403", description = "No tiene permisos para consultar doctores")
    })
    public ResponseEntity<List<DoctorShortResponse>> getAllDoctors() {
        List<Doctor> doctors = doctorService.findAllDoctors();

        List<UUID> ids = doctors.stream()
                .map(Doctor::getPersonId)
                .toList();

        Map<UUID, String> names = personExternalService.getPersonNames(ids);

        List<DoctorShortResponse> responses = doctors.stream()
                .map(d -> DoctorShortResponse.fromEntity(d,names.get(d.getPersonId())))
                .toList();

        return ResponseEntity.ok(responses);
    }

    @GetMapping("/detailed")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Listar doctores con información detallada",
            description = "Devuelve una página con doctores registrados, incluyendo información detallada, "
                    + "ordenada y paginada según los parámetros recibidos. Permite filtrar por nombre o cédula.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Doctores obtenidos correctamente"),
            @ApiResponse(responseCode = "401", description = "No autenticado"),
            @ApiResponse(responseCode = "403", description = "No tiene permisos para consultar doctores")
    })
    public ResponseEntity<PageResponse<DoctorDetailedResponse>> getDoctorsDetailed(
            @Parameter(description = "Parámetros de paginación y ordenamiento")
            @PageableDefault(page = 0, size = 9, sort = "name", direction = Sort.Direction.ASC) Pageable pageable,
            @Parameter(description = "Término de búsqueda por nombre completo o cédula del doctor")
            @RequestParam(required = false) String search
    ) {
        Page<DoctorDetailedResponse> doctors = doctorService.findAllDoctorsDetailed(pageable, search);
        return ResponseEntity.ok(PageResponse.from(doctors));
    }

    @PutMapping("/{doctorId}/specialties")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Actualizar especialidades de un doctor",
            description = "Reemplaza las especialidades actuales del doctor por las proporcionadas en la solicitud.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Especialidades actualizadas correctamente"),
            @ApiResponse(responseCode = "401", description = "No autenticado"),
            @ApiResponse(responseCode = "403", description = "No tiene permisos para actualizar especialidades"),
            @ApiResponse(responseCode = "404", description = "No existe un doctor con el identificador proporcionado")
    })
    public ResponseEntity<Void> changeSpecialties(
            @Parameter(description = "Identificador único (UUID) del doctor")
            @PathVariable UUID doctorId,
            @Parameter(description = "Lista de especialidades que tendrá el doctor")
            @RequestBody List<SpecialtyCode> specialties) {
        doctorService.changeSpecialties(doctorId, specialties);
        return ResponseEntity.noContent().build();
    }


    @GetMapping("/all-specialties")
    @PreAuthorize("hasAnyRole('ADMIN')")
    @Operation(summary = "Listar todas las especialidades médicas",
            description = "Devuelve todas las especialidades médicas registradas en el sistema, sin filtrado por doctor o paciente.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Especialidades obtenidas correctamente"),
            @ApiResponse(responseCode = "401", description = "No autenticado"),
            @ApiResponse(responseCode = "403", description = "No tiene permisos para consultar especialidades")
    })
    public ResponseEntity<List<SpecialtyCode>> getAllSpecialties() {
        List<SpecialtyCode> specialties = doctorService.getAllSpecialties();
        return ResponseEntity.ok(specialties);
    }

    @PutMapping("/{doctorId}/enable")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Habilitar un doctor",
            description = "Habilita un doctor previamente deshabilitado. La activación solo se realiza si el doctor " +
                    "cumple todas las condiciones necesarias para prestar atención.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Doctor habilitado correctamente"),
            @ApiResponse(responseCode = "400", description = "El doctor no cumple los requisitos para ser habilitado"),
            @ApiResponse(responseCode = "401", description = "No autenticado"),
            @ApiResponse(responseCode = "403", description = "No tiene permisos para habilitar doctores"),
            @ApiResponse(responseCode = "404", description = "No existe un doctor con el identificador proporcionado"),
            @ApiResponse(responseCode = "409", description = "Conflicto con las fechas de inicio y fin de labor del doctor")
    })
    public ResponseEntity<Void> enableDoctor(
            @Parameter(description = "Identificador único (UUID) del doctor")
            @PathVariable UUID doctorId
    ) {
        doctorService.enableDoctor(doctorId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{doctorId}/update-info")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Actualizar el período laboral de un doctor",
            description = "Modifica la fecha de inicio y la fecha de finalización de labores del doctor.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Período laboral actualizado correctamente"),
            @ApiResponse(responseCode = "401", description = "No autenticado"),
            @ApiResponse(responseCode = "403", description = "No tiene permisos para actualizar el período laboral"),
            @ApiResponse(responseCode = "404", description = "No existe un doctor con el identificador proporcionado"),
            @ApiResponse(responseCode = "409", description = "Conflicto con las fechas de inicio y fin de labor proporcionadas"),
            @ApiResponse(responseCode = "400", description = "El intervalo  o  ventana de agendamiento proporcionada no es válida")
    })
    public ResponseEntity<Void> updateDoctorInfo(
            @Parameter(description = "Identificador único (UUID) del doctor")
            @PathVariable UUID doctorId,
            @Parameter(description = "Nueva fecha de inicio de labores")
            @RequestParam LocalDate laborStart,
            @Parameter(description = "Nueva fecha de finalización de labores")
            @RequestParam LocalDate laborEnd,
            @Parameter(description = "Nueva duración de las citas, expresada en minutos")
            @RequestParam int appointmentInterval,
            @Parameter(description = "Nueva duración de las ventana de agendamiento " +
                    "expresada en semanas")
            @RequestParam int bookingWindowWeeks
    ) {
        doctorService.updateDoctorInfo(doctorId, laborStart, laborEnd,appointmentInterval,bookingWindowWeeks);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{doctorId}/disable")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Deshabilitar un doctor",
            description = "Deshabilita un doctor. La desactivación solo se realiza si el doctor aun tiene citas.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Doctor deshabilitado correctamente"),
            @ApiResponse(responseCode = "401", description = "No autenticado"),
            @ApiResponse(responseCode = "403", description = "No tiene permisos para deshabilitar doctores"),
            @ApiResponse(responseCode = "404", description = "No existe un doctor con el identificador proporcionado"),
            @ApiResponse(responseCode = "409", description = "El doctor aún tiene citas por atender")
    })
    public ResponseEntity<Void> disableDoctor(
            @Parameter(description = "Identificador único (UUID) del doctor")
            @PathVariable UUID doctorId
    ) {
        doctorService.disableDoctor(doctorId);
        return ResponseEntity.noContent().build();
    }
}
