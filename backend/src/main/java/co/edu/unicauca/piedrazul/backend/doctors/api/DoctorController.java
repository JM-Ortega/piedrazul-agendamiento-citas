package co.edu.unicauca.piedrazul.backend.doctors.api;

import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.output.DoctorDetailedResponse;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.output.DoctorResponse;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.output.DoctorShortResponse;
import co.edu.unicauca.piedrazul.backend.doctors.application.DoctorService;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Doctor;
import co.edu.unicauca.piedrazul.backend.doctors.exception.DateConflictException;
import co.edu.unicauca.piedrazul.backend.doctors.exception.DoctorNotFoundException;
import co.edu.unicauca.piedrazul.backend.doctors.exception.DoctorValidationException;
import co.edu.unicauca.piedrazul.backend.shared.enums.SpecialtyCode;
import co.edu.unicauca.piedrazul.backend.shared.pagination.PageResponse;
import co.edu.unicauca.piedrazul.backend.user.PersonExternalService;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/doctor")
public class DoctorController {
    private final DoctorService doctorService;
    private final PersonExternalService personExternalService;

    public DoctorController(DoctorService doctorService, PersonExternalService personExternalService) {
        this.doctorService = doctorService;
        this.personExternalService = personExternalService;
    }

    /**
     * Obtiene la información del doctor asociado al usuario autenticado.
     * <p>
     * El doctor se identifica a partir del identificador del usuario contenido
     * en el token JWT de autenticación.
     * </p>
     *
     * <p>
     * Requiere que el usuario autenticado posea el rol {@code DOCTOR}.
     * </p>
     *
     * @param jwt token JWT del usuario autenticado.
     * @return un {@link DoctorDetailedResponse} con la información detallada
     * del doctor autenticado.
     * @throws DoctorNotFoundException si el usuario autenticado no tiene un doctor asociado.
     * @throws AuthorizationDeniedException si el usuario autenticado no tiene permisos para acceder al recurso.
     * @throws AccessDeniedException si el acceso al recurso es denegado por Spring Security.
     */
    @GetMapping("/me")
    @PreAuthorize("hasRole('DOCTOR')")
    public DoctorDetailedResponse findMe(@AuthenticationPrincipal Jwt jwt) {
        Doctor doctor = doctorService.findByUserId(UUID.fromString(jwt.getSubject()));

        Map<UUID, String> names = personExternalService.getPersonNames(List.of(doctor.getPersonId()));

        return DoctorDetailedResponse.fromEntity(doctor, names.get(doctor.getPersonId()));
    }

    /**
     * Obtiene todos los doctores registrados en el sistema.
     * <p>
     * La respuesta incluye las especialidades y el nombre del doctor.
     *
     * <p>
     * Requiere que el usuario autenticado posea alguno de los roles
     * {@code SCHEDULER}, {@code PATIENT} o {@code DOCTOR}.
     * </p>
     *
     * @return un {@link ResponseEntity} con estado {@code 200 OK} que contiene
     * una lista de {@link DoctorShortResponse}. La lista puede estar vacía si
     * no existen doctores registrados.
     * @throws AuthorizationDeniedException si el usuario autenticado no tiene permisos para acceder al recurso.
     * @throws AccessDeniedException si el acceso al recurso es denegado por Spring Security.
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('SCHEDULER', 'PATIENT', 'DOCTOR')")
    public ResponseEntity<?> getAllDoctors() {
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

    /**
     * Obtiene una página de doctores con información detallada.
     * <p>
     * Los resultados se encuentran paginados y ordenados por nombre de forma ascendente.
     * Por defecto, el tamaño de la página es de nueve doctores, comenzando desde la página cero.
     * </p>
     *
     * <p>
     * Requiere que el usuario autenticado posea el rol {@code ADMIN}.
     * </p>
     *
     * @param pageable objeto de paginación que define el índice, tamaño y ordenamiento de la consulta.
     * @return un {@link ResponseEntity} con estado {@code 200 OK} que contiene
     * un {@link PageResponse} de {@link DoctorDetailedResponse}.
     */
    @GetMapping("/detailed")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PageResponse<DoctorDetailedResponse>> getDoctorsDetailed(
            @PageableDefault(page = 0, size = 9, sort = "name", direction = Sort.Direction.ASC) Pageable pageable
    ) {
        Page<DoctorDetailedResponse> doctors = doctorService.findAllDoctorsDetailed(pageable);
        return ResponseEntity.ok(PageResponse.from(doctors));
    }

    /**
     * Obtiene las especialidades disponibles para la asignación de citas.
     * <p>
     * Si se proporciona un paciente, la lista podrá filtrarse de acuerdo con las
     * reglas de negocio aplicables para dicho paciente.
     * </p>
     *
     * <p>
     * Requiere que el usuario autenticado posea alguno de los roles
     * {@code SCHEDULER}, {@code PATIENT} o {@code DOCTOR}.
     * </p>
     *
     * @param patientId identificador del paciente para filtrar las especialidades.
     * Puede ser {@code null}.
     * @return un {@link ResponseEntity} con estado {@code 200 OK} que contiene
     * una lista de {@link SpecialtyCode}.
     * @throws AuthorizationDeniedException si el usuario autenticado no tiene permisos para acceder al recurso.
     * @throws AccessDeniedException si el acceso al recurso es denegado por Spring Security.
     */
    @GetMapping("/patients/specialties")
    @PreAuthorize("hasAnyRole('SCHEDULER', 'PATIENT', 'DOCTOR')")
    public ResponseEntity<List<SpecialtyCode>> getSpecialties(
            @RequestParam(required = false) UUID patientId) {
        List<SpecialtyCode> specialties = doctorService.getSpecialties(patientId);
        return ResponseEntity.ok(specialties);
    }


    /**
     * Actualiza las especialidades asociadas a un doctor.
     * <p>
     * Las especialidades actuales serán reemplazadas por las proporcionadas en la
     * solicitud.
     * </p>
     *
     * <p>
     * Requiere que el usuario autenticado posea el rol {@code ADMIN}.
     * </p>
     *
     * @param doctorId identificador único (UUID) del doctor.
     * @param specialties lista de especialidades que tendrá el doctor.
     * @return un {@link ResponseEntity} con estado {@code 204 No Content} si la actualización fue exitosa.
     * @throws DoctorNotFoundException si no existe un doctor asociado al identificador proporcionado.
     * @throws AuthorizationDeniedException si el usuario autenticado no tiene permisos para acceder al recurso.
     * @throws AccessDeniedException si el acceso al recurso es denegado por Spring Security.
     */
    @PutMapping("/{doctorId}/specialties")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> changeSpecialties(
            @PathVariable UUID doctorId,
            @RequestBody List<SpecialtyCode> specialties) {
        doctorService.changeSpecialties(doctorId, specialties);
        return ResponseEntity.noContent().build();
    }

    /**
     * Obtiene todas las especialidades médicas registradas en el sistema.
     *
     * <p>
     * Requiere que el usuario autenticado posea el rol {@code ADMIN}.
     * </p>
     *
     * @return un {@link ResponseEntity} con estado {@code 200 OK} que contiene
     * una lista de {@link SpecialtyCode}.
     * @throws AuthorizationDeniedException si el usuario autenticado no tiene permisos para acceder al recurso.
     * @throws AccessDeniedException si el acceso al recurso es denegado por Spring Security.
     */
    @GetMapping("/all-specialties")
    @PreAuthorize("hasAnyRole('ADMIN')")
    public ResponseEntity<?> getAllSpecialties() {
        List<SpecialtyCode> specialties = doctorService.getAllSpecialties();
        return ResponseEntity.ok(specialties);
    }

    /**
     * Obtiene los doctores que atienden una especialidad determinada.
     *
     * <p>
     * Requiere que el usuario autenticado posea alguno de los roles
     * {@code SCHEDULER}, {@code PATIENT} o {@code DOCTOR}.
     * </p>
     *
     * @param specialty especialidad por la cual se filtrarán los doctores.
     * @return un {@link ResponseEntity} con estado {@code 200 OK} que contiene
     * una lista de {@link DoctorResponse}.
     * @throws AuthorizationDeniedException si el usuario autenticado no tiene permisos para acceder al recurso.
     * @throws AccessDeniedException si el acceso al recurso es denegado por Spring Security.
     */
    @GetMapping("/specialty/{specialty}")
    @PreAuthorize("hasAnyRole('SCHEDULER', 'PATIENT', 'DOCTOR')")
    public ResponseEntity<?> getDoctorsBySpecialty(@PathVariable SpecialtyCode specialty) {
        List<Doctor> doctors = doctorService.getDoctorBySpeciality(specialty);

        List<UUID> ids = doctors.stream()
                .map(Doctor::getPersonId)
                .toList();

        Map<UUID, String> names = personExternalService.getPersonNames(ids);

        List<DoctorResponse> responses = doctors.stream()
                .map(d -> DoctorResponse.fromEntity(d, names.get(d.getPersonId())))
                .toList();
        return ResponseEntity.ok(responses);
    }

    /**
     * Habilita un doctor previamente deshabilitado.
     * <p>
     * La activación solo será posible si el doctor cumple todas las condiciones
     * necesarias para prestar atención, según las reglas de negocio del sistema.
     * </p>
     *
     * <p>
     * Requiere que el usuario autenticado posea el rol {@code ADMIN}.
     * </p>
     *
     * @param doctorId identificador único (UUID) del doctor.
     * @return un {@link ResponseEntity} con estado {@code 204 No Content} si la operación fue exitosa.
     * @throws DoctorNotFoundException si no existe un doctor asociado al identificador proporcionado.
     * @throws DoctorValidationException si el doctor no cumple los requisitos para ser habilitado.
     * @throws DateConflictException si hay conflictos con las fechas de incio y fin labor.
     * @throws AuthorizationDeniedException si el usuario autenticado no tiene permisos para acceder al recurso.
     * @throws AccessDeniedException si el acceso al recurso es denegado por Spring Security.
     */
    @PutMapping("/{doctorId}/enable")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> enableDoctor(
            @PathVariable UUID doctorId
    ) {
        doctorService.enableDoctor(doctorId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Actualiza el período laboral de un doctor.
     * <p>
     * Modifica la fecha de inicio y la fecha de finalización de labores del doctor.
     * </p>
     *
     * <p>
     * Requiere que el usuario autenticado posea el rol {@code ADMIN}.
     * </p>
     *
     * @param doctorId identificador único (UUID) del doctor.
     * @param laborStart nueva fecha de inicio de labores.
     * @param laborEnd nueva fecha de finalización de labores.
     * @return un {@link ResponseEntity} con estado {@code 204 No Content} si la actualización fue exitosa.
     * @throws DoctorNotFoundException si no existe un doctor asociado al identificador proporcionado.
     * @throws DateConflictException si hay conflictos con las fechas de incio y fin labor.
     * @throws AuthorizationDeniedException si el usuario autenticado no tiene permisos para acceder al recurso.
     * @throws AccessDeniedException si el acceso al recurso es denegado por Spring Security.
     */
    @PutMapping("/{doctorId}/labor-date")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> updateDoctorLaborDate(
            @PathVariable UUID doctorId,
            @RequestParam LocalDate laborStart,
            @RequestParam LocalDate laborEnd
    ) {
        doctorService.updateDoctorLaborDate(doctorId, laborStart, laborEnd);
        return ResponseEntity.noContent().build();
    }

    /**
     * Actualiza la duración de las citas de un doctor.
     *
     * <p>
     * Requiere que el usuario autenticado posea el rol {@code ADMIN}.
     * </p>
     *
     * @param doctorId identificador único (UUID) del doctor.
     * @param appointmentInterval nueva duración de las citas, expresada en minutos.
     * @return un {@link ResponseEntity} con estado {@code 204 No Content} si la actualización fue exitosa.
     * @throws DoctorNotFoundException si no existe un doctor asociado al identificador proporcionado.
     * @throws DoctorValidationException si el intervalo proporcionado no es válido.
     * @throws AuthorizationDeniedException si el usuario autenticado no tiene permisos para acceder al recurso.
     * @throws AccessDeniedException si el acceso al recurso es denegado por Spring Security.
     */
    @PutMapping("/{doctorId}/appointment-interval")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> updateDoctorAppointmentInterval(
            @PathVariable UUID doctorId,
            @RequestParam int appointmentInterval
    ) {
        doctorService.updateDoctorAppointmentInterval(doctorId, appointmentInterval);
        return ResponseEntity.noContent().build();
    }

    /**
     * Deshabilita un doctor.
     * <p>
     * Si el parámetro {@code force} es {@code true}, la deshabilitación se realizará
     * ignorando las validaciones que permitan forzar la operación.
     * </p>
     *
     * <p>
     * Requiere que el usuario autenticado posea el rol {@code ADMIN}.
     * </p>
     *
     * @param doctorId identificador único (UUID) del doctor.
     * @param force indica si la deshabilitación debe forzarse.
     * @return un {@link ResponseEntity} con estado {@code 204 No Content} si la operación fue exitosa.
     * @throws DoctorNotFoundException si no existe un doctor asociado al identificador proporcionado.
     * @throws DateConflictException si se quiere deshabilitar un doctor que aun no termina su periodo laboral.
     * @throws AuthorizationDeniedException si el usuario autenticado no tiene permisos para acceder al recurso.
     * @throws AccessDeniedException si el acceso al recurso es denegado por Spring Security.
     */
    @PutMapping("/{doctorId}/disable")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> disableDoctor(
            @PathVariable UUID doctorId,
            @RequestParam(defaultValue = "false") boolean force
    ) {
        doctorService.disableDoctor(doctorId, force);
        return ResponseEntity.noContent().build();
    }
}
