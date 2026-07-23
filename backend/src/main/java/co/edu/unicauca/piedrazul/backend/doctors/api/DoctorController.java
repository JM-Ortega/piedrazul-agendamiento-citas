package co.edu.unicauca.piedrazul.backend.doctors.api;

import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.output.DoctorDetailedResponse;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.output.DoctorResponse;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.output.DoctorShortResponse;
import co.edu.unicauca.piedrazul.backend.doctors.application.DoctorService;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Doctor;
import co.edu.unicauca.piedrazul.backend.shared.enums.SpecialtyCode;
import co.edu.unicauca.piedrazul.backend.user.PersonExternalService;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/doctor/doctors")
public class DoctorController {
    private final DoctorService doctorService;
    private final PersonExternalService personExternalService;

    public DoctorController(DoctorService doctorService, PersonExternalService personExternalService) {
        this.doctorService = doctorService;
        this.personExternalService = personExternalService;
    }

    /**
     * Obtener los datos del doctor autenticado (basado en el JWT)
     * @param jwt
     * @return
     */
    @GetMapping("/me")
    @PreAuthorize("hasRole('DOCTOR')")
    public DoctorDetailedResponse findMe(@AuthenticationPrincipal Jwt jwt) {
        Doctor doctor = doctorService.findByUserId(UUID.fromString(jwt.getSubject()));

        Map<UUID, String> names = personExternalService.getPersonNames(List.of(doctor.getPersonId()));

        return DoctorDetailedResponse.fromEntity(doctor, names.get(doctor.getPersonId()));
    }

    /**
     * Listar todos los doctores
     * @return Lista de todos los doctores
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
     * Paginación que lista siempre 5 doctores
     * @return Lista de todos los doctores
     */
    @GetMapping("/detailed")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> getDoctors(
            @RequestParam(defaultValue = "0") @Min(0) int page) {

        Pageable pageable = PageRequest.of(page, 5);

        Page<DoctorDetailedResponse> response = doctorService.findAllDoctorsDetailed(pageable);

        return ResponseEntity.ok(response);
    }

    /**
     * Obtener un doctor por su ID
     * @param doctorId ID del doctor
     * @return Los datos del doctor
     */
    @GetMapping("/{doctorId}")
    @PreAuthorize("hasRole('DOCTOR')")
    public ResponseEntity<?> getDoctorById(@PathVariable UUID doctorId) {
        Doctor doctor = doctorService.getDoctorById(doctorId);
        Map<UUID, String> names = personExternalService.getPersonNames(List.of(doctorId));

        return ResponseEntity.ok(DoctorShortResponse.fromEntity(doctor,names.get(doctor.getPersonId())));
    }

    /**
     * Obtiene las especialidades disponibles para un paciente
     * @param patientId Id del paciente (opcional)
     * @return Lista de especialidades
     */
    @GetMapping("/patients/specialties")
    @PreAuthorize("hasAnyRole('SCHEDULER', 'PATIENT', 'DOCTOR')")
    public ResponseEntity<List<SpecialtyCode>> getSpecialties(@RequestParam(required = false) UUID patientId) {
        List<SpecialtyCode> specialties = doctorService.getSpecialties(patientId);
        return ResponseEntity.ok(specialties);
    }


    /**
     * Actualiza las especialidades de un doctor específico
     * @param specialties Especialidades a agregar para el doctor
     * @param doctorId Id del doctor
     * @return Sin contenido
     */
    @PutMapping("/{doctorId}/specialties")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> changeSpecialties(
            @PathVariable UUID doctorId,
            @RequestBody List<SpecialtyCode> specialties) {
        doctorService.changeSpecialties(doctorId, specialties);
        return ResponseEntity.noContent().build();
    }

    /**
     * Obtiene todas las especialidades de los medicos
     * @return Lista de especialidades
     */
    @GetMapping("/all-specialties")
    @PreAuthorize("hasAnyRole('ADMIN')")
    public ResponseEntity<?> getAllSpecialties() {
        List<SpecialtyCode> specialties = doctorService.getAllSpecialties();
        return ResponseEntity.ok(specialties);
    }

    /**
     * Obtener doctores por especialidad
     * @param specialty Especialidad a buscar
     * @return Lista de doctores con esa especialidad
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
     * Habilitar un doctor (reactivar después de estar deshabilitado)
     * @param doctorId ID del doctor
     * @param laborStart Nueva fecha de inicio de labores
     * @param laborEnd Nueva fecha de fin de labores
     * @return Sin contenido
     */
    @PutMapping("/{doctorId}/enable")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> enableDoctor(
            @PathVariable UUID doctorId,
            @RequestParam LocalDate laborStart,
            @RequestParam LocalDate laborEnd
    ) {
        doctorService.enableDoctor(doctorId, laborStart, laborEnd);
        return ResponseEntity.noContent().build();
    }

    /**
     * Actualizar la fecha de inicio laboral de un doctor
     * @param doctorId ID del doctor
     * @param laborStart Nueva fecha de inicio
     * @return Sin contenido
     */
    @PutMapping("/{doctorId}/labor-start")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> updateDoctorLaborStart(
            @PathVariable UUID doctorId,
            @RequestParam LocalDate laborStart
    ) {
        doctorService.updateDoctorLaborStart(doctorId, laborStart);
        return ResponseEntity.noContent().build();
    }

    /**
     * Actualizar la fecha de finalización laboral de un doctor
     * @param doctorId ID del doctor
     * @param laborEnd Nueva fecha de finalización
     * @return Sin contenido
     */
    @PutMapping("/{doctorId}/labor-end")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> updateDoctorLaborEnd(
            @PathVariable UUID doctorId,
            @RequestParam LocalDate laborEnd
    ) {
        doctorService.updateDoctorLaborEnd(doctorId, laborEnd);
        return ResponseEntity.noContent().build();
    }

    /**
     * Actualizar el intervalo de atención de un doctor
     * @param doctorId ID del doctor
     * @param appointmentInterval Nuevo intervalo en minutos
     * @return Sin contenido
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
     * Deshabilitar un doctor
     * @param doctorId ID del doctor
     * @param force Forzar deshabilitación incluso si aún no ha iniciado labores
     * @return Sin contenido
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

    /**
     * Actualizar el estado del doctor basado en las fechas de labor
     * (Se ejecuta automáticamente para sincronizar con la fecha actual)
     * @param doctorId ID del doctor
     * @return Sin contenido
     */
    @PutMapping("/{doctorId}/sync-status")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> updateDoctorStatus(@PathVariable UUID doctorId) {
        doctorService.updateDoctorStatus(doctorId);
        return ResponseEntity.noContent().build();
    }


}
