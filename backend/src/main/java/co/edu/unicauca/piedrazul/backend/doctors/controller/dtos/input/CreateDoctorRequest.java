package co.edu.unicauca.piedrazul.backend.doctors.controller.dtos.input;

import co.edu.unicauca.piedrazul.backend.doctors.model.models.Schedule;
import co.edu.unicauca.piedrazul.backend.doctors.model.models.Specialty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record CreateDoctorRequest(
        // Viene de Keycloak
        @NotBlank UUID idUser,
        @NotBlank String firstName,
        @NotBlank String lastName,
        @NotNull List<Specialty> specialty,
        LocalDate laborStart,
        LocalDate laborEnd,
        int appointmentInterval,
        int schedulableWeeks,
        List<Schedule> schedules
) {}
