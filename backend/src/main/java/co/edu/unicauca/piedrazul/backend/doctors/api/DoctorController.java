package co.edu.unicauca.piedrazul.backend.doctors.api;

import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.input.CreateDoctorRequest;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.output.DoctorResponse;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Doctor;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Specialty;
import co.edu.unicauca.piedrazul.backend.doctors.application.DoctorService;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/doctor/doctors")
public class DoctorController {
    @Autowired
    private DoctorService doctorService;

    /**
     * Crear un nuevo doctor
     * @param request Datos del doctor
     * @return El doctor creado
     */
    @PostMapping
    public ResponseEntity<?> createDoctor(@RequestBody CreateDoctorRequest request) {
        try {
            DoctorResponse response = doctorService.createDoctor(request);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("Error creating doctor: " + e.getMessage());
        }
    }

    /**
     * Listar todos los doctores
     * @return Lista de todos los doctores
     */
    @GetMapping
    public ResponseEntity<?> getAllDoctors() {
        try {
            List<Doctor> doctors = doctorService.findAllDoctors();
            List<DoctorResponse> responses = doctors.stream()
                    .map(DoctorResponse::fromEntity)
                    .toList();
            return ResponseEntity.ok(responses);
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("Error retrieving doctors: " + e.getMessage());
        }
    }

    /**
     * Obtener un doctor por su ID
     * @param doctorId ID del doctor
     * @return Los datos del doctor
     */
    @GetMapping("/{doctorId}")
    public ResponseEntity<?> getDoctorById(@PathVariable UUID doctorId) {
        try {
            Doctor doctor = doctorService.getDoctorById(doctorId);
            return ResponseEntity.ok(DoctorResponse.fromEntity(doctor));
        } catch (EntityNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("Doctor not found: " + e.getMessage());
        }
    }

    /**
     * Obtener doctores por especialidad
     * @param specialty Especialidad a buscar
     * @return Lista de doctores con esa especialidad
     */
    @GetMapping("/specialty/{specialty}")
    public ResponseEntity<?> getDoctorsBySpecialty(@PathVariable Specialty specialty) {
        try {
            List<Doctor> doctors = doctorService.getDoctorBySpeciality(specialty);
            List<DoctorResponse> responses = doctors.stream()
                    .map(DoctorResponse::fromEntity)
                    .toList();
            return ResponseEntity.ok(responses);
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("Error retrieving doctors by specialty: " + e.getMessage());
        }
    }

    /**
     * Habilitar un doctor (reactivar después de estar deshabilitado)
     * @param doctorId ID del doctor
     * @param laborStart Nueva fecha de inicio de labores
     * @param laborEnd Nueva fecha de fin de labores
     * @return El doctor actualizado
     */
    @PutMapping("/{doctorId}/enable")
    public ResponseEntity<?> enableDoctor(
            @PathVariable UUID doctorId,
            @RequestParam LocalDate laborStart,
            @RequestParam LocalDate laborEnd
    ) {
        try {
            DoctorResponse response = doctorService.enableDoctor(doctorId, laborStart, laborEnd);
            return ResponseEntity.ok(response);
        } catch (EntityNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("Doctor not found: " + e.getMessage());
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("Error enabling doctor: " + e.getMessage());
        }
    }

    /**
     * Deshabilitar un doctor
     * @param doctorId ID del doctor
     * @param force Forzar deshabilitación incluso si aún no ha iniciado labores
     * @return El doctor actualizado
     */
    @PutMapping("/{doctorId}/disable")
    public ResponseEntity<?> disableDoctor(
            @PathVariable UUID doctorId,
            @RequestParam(defaultValue = "false") boolean force
    ) {
        try {
            DoctorResponse response = doctorService.disableDoctor(doctorId, force);
            return ResponseEntity.ok(response);
        } catch (EntityNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("Doctor not found: " + e.getMessage());
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("Error disabling doctor: " + e.getMessage());
        }
    }

    /**
     * Actualizar el estado del doctor basado en las fechas de labor
     * (Se ejecuta automáticamente para sincronizar con la fecha actual)
     * @param doctorId ID del doctor
     * @return Sin contenido
     */
    @PutMapping("/{doctorId}/sync-status")
    public ResponseEntity<?> updateDoctorStatus(@PathVariable UUID doctorId) {
        try {
            doctorService.updateDoctorStatus(doctorId);
            return ResponseEntity.noContent().build();
        } catch (EntityNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("Doctor not found: " + e.getMessage());
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("Error updating doctor status: " + e.getMessage());
        }
    }
}
