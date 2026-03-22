package co.edu.unicauca.piedrazul.backend.doctors.api;

import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.input.CreateScheduleRequest;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.output.AvailableIntervalsResponse;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.output.ScheduleResponse;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Schedule;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Workday;
import co.edu.unicauca.piedrazul.backend.doctors.application.DoctorService;
import co.edu.unicauca.piedrazul.backend.doctors.application.ScheduleService;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/doctor/schedules")
public class SheduleController {
    @Autowired
    private ScheduleService scheduleService;

    @Autowired
    private DoctorService doctorService;

    /**
     * Crear un nuevo horario para un doctor
     * @param doctorId ID del doctor
     * @param request Datos del horario (startTime, endTime, workday)
     * @return El horario creado
     */
    @PostMapping("/{doctorId}")
    public ResponseEntity<?> createSchedule(
            @PathVariable UUID doctorId,
            @RequestBody CreateScheduleRequest request
    ) {
        try {
            var doctor = doctorService.getDoctorById(doctorId);
            
            Schedule schedule = new Schedule(
                    doctor,
                    request.startTime(),
                    request.endTime(),
                    request.workday()
            );
            
            Schedule savedSchedule = scheduleService.addSchedule(doctor, schedule);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(ScheduleResponse.fromEntity(savedSchedule));
        } catch (EntityNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("Doctor not found: " + e.getMessage());
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("Error creating schedule: " + e.getMessage());
        }
    }

    /**
     * Actualizar el horario de un doctor para un día específico
     * @param doctorId ID del doctor
     * @param workday Día de la semana
     * @param request Nuevos datos del horario
     * @return El horario actualizado
     */
    @PutMapping("/{doctorId}/{workday}")
    public ResponseEntity<?> updateSchedule(
            @PathVariable UUID doctorId,
            @PathVariable Workday workday,
            @RequestBody CreateScheduleRequest request
    ) {
        try {
            var doctor = doctorService.getDoctorById(doctorId);
            
            Schedule newScheduleData = new Schedule(
                    doctor,
                    request.startTime(),
                    request.endTime(),
                    workday
            );
            
            Schedule updatedSchedule = scheduleService.updateScheduleByWorkday(doctor, workday, newScheduleData);
            return ResponseEntity.ok(ScheduleResponse.fromEntity(updatedSchedule));
        } catch (EntityNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("Doctor not found: " + e.getMessage());
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("Error updating schedule: " + e.getMessage());
        }
    }

    /**
     * Obtener todos los horarios de un doctor
     * @param doctorId ID del doctor
     * @return Lista de horarios del doctor
     */
    @GetMapping("/{doctorId}")
    public ResponseEntity<?> getSchedulesByDoctor(@PathVariable UUID doctorId) {
        try {
            var doctor = doctorService.getDoctorById(doctorId);
            List<Schedule> schedules = scheduleService.getSchedulesByDoctor(doctor);
            List<ScheduleResponse> responses = schedules.stream()
                    .map(ScheduleResponse::fromEntity)
                    .toList();
            return ResponseEntity.ok(responses);
        } catch (EntityNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("Doctor not found: " + e.getMessage());
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("Error retrieving schedules: " + e.getMessage());
        }
    }

    /**
     * Obtener los intervalos disponibles para agendar citas en un día específico
     * @param doctorId ID del doctor
     * @param workday Día de la semana
     * @return Lista de horarios disponibles
     */
    @GetMapping("/{doctorId}/available-intervals/{workday}")
    public ResponseEntity<?> getAvailableIntervals(
            @PathVariable UUID doctorId,
            @PathVariable Workday workday
    ) {
        try {
            var doctor = doctorService.getDoctorById(doctorId);
            List<LocalTime> availableIntervals = scheduleService.getAvailableIntervalsByWorkday(doctor, workday);
            
            AvailableIntervalsResponse response = new AvailableIntervalsResponse(
                    workday.toString(),
                    availableIntervals
            );
            
            return ResponseEntity.ok(response);
        } catch (EntityNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("Doctor not found: " + e.getMessage());
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("Error retrieving available intervals: " + e.getMessage());
        }
    }
}
