package co.edu.unicauca.piedrazul.backend.appointment.controller.controllers;

import co.edu.unicauca.piedrazul.backend.appointment.controller.dtos.appointmentCreateRequest;
import co.edu.unicauca.piedrazul.backend.appointment.model.models.appointment;
import co.edu.unicauca.piedrazul.backend.appointment.model.service.appointmentService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@RestController
@RequestMapping("/API/appointment")
public class appointmentController {

    private final appointmentService appointmentService;

    public appointmentController(appointmentService appointmentService) {
        this.appointmentService = appointmentService;
    }

    @PostMapping
    public ResponseEntity<appointment> create(@Valid @RequestBody appointmentCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(appointmentService.create(request));
    }

    @DeleteMapping("/{idAppointment}")
    public ResponseEntity<Void> delete(@PathVariable long idAppointment) {
        try {
            appointmentService.delete(idAppointment);
            return ResponseEntity.noContent().build();
        } catch (NoSuchElementException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    @GetMapping
    public List<appointment> list(
            @RequestParam(required = false) UUID doctorId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        if (doctorId != null && date != null) {
            return appointmentService.listByDoctorAndDate(doctorId, date);
        }
        if (doctorId != null) {
            return appointmentService.listByDoctor(doctorId);
        }
        if (date != null) {
            return appointmentService.listByDate(date);
        }
        return appointmentService.listAll();
    }
}

