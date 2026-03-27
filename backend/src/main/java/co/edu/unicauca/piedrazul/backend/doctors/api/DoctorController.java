package co.edu.unicauca.piedrazul.backend.doctors.api;

import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.input.CreateDoctorRequest;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.output.DoctorDetailedResponse;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.output.DoctorShortResponse;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.output.DoctorResponse;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Doctor;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Specialty;
import co.edu.unicauca.piedrazul.backend.doctors.application.DoctorService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/doctor/doctors")
public class DoctorController {
    private final DoctorService doctorService;

    public DoctorController(DoctorService doctorService) {
        this.doctorService = doctorService;
    }

    /**
     * Crear un nuevo doctor
     * @param request Datos del doctor
     * @return Sin contenido
     */
    @PostMapping
    public ResponseEntity<?> createDoctor(@RequestBody CreateDoctorRequest request) {
        doctorService.createDoctor(request);
        return ResponseEntity.noContent().build();
    }

    /**
     * Listar todos los doctores
     * @return Lista de todos los doctores
     */
    @GetMapping
    public ResponseEntity<?> getAllDoctors() {
        List<Doctor> doctors = doctorService.findAllDoctors();
        List<DoctorShortResponse> responses = doctors.stream()
                .map(DoctorShortResponse::fromEntity)
                .toList();
        return ResponseEntity.ok(responses);
    }

    /**
     * Listar todos los doctores
     * @return Lista de todos los doctores
     */
    @GetMapping("/detailed")
    public ResponseEntity<?> getAllDoctorsDetailed() {
        List<Doctor> doctors = doctorService.findAllDoctors();
        List<DoctorDetailedResponse> responses = doctors.stream()
                .map(DoctorDetailedResponse::fromEntity)
                .toList();
        return ResponseEntity.ok(responses);
    }

    /**
     * Obtener un doctor por su ID
     * @param doctorId ID del doctor
     * @return Los datos del doctor
     */
    @GetMapping("/{doctorId}")
    public ResponseEntity<?> getDoctorById(@PathVariable UUID doctorId) {
        Doctor doctor = doctorService.getDoctorById(doctorId);
        return ResponseEntity.ok(DoctorShortResponse.fromEntity(doctor));
    }

    /**
     * Obtiene las especialidades de los medicos activos
     * @return Lista de especialidades
     */
    @GetMapping("/specialty")
    public ResponseEntity<?> getSpecialties() {
        List<Specialty> specialties = doctorService.getSpecialties();
        return ResponseEntity.ok(specialties);
    }

    /**
     * Obtener doctores por especialidad
     * @param specialty Especialidad a buscar
     * @return Lista de doctores con esa especialidad
     */
    @GetMapping("/specialty/{specialty}")
    public ResponseEntity<?> getDoctorsBySpecialty(@PathVariable Specialty specialty) {
        List<Doctor> doctors = doctorService.getDoctorBySpeciality(specialty);
        List<DoctorResponse> responses = doctors.stream()
                .map(DoctorResponse::fromEntity)
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
    public ResponseEntity<?> updateDoctorStatus(@PathVariable UUID doctorId) {
        doctorService.updateDoctorStatus(doctorId);
        return ResponseEntity.noContent().build();
    }


}
