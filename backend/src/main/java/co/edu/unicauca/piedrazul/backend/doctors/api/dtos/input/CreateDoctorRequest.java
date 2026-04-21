package co.edu.unicauca.piedrazul.backend.doctors.api.dtos.input;

import co.edu.unicauca.piedrazul.backend.doctors.domain.Specialty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.LocalDate;
import java.util.List;

public record CreateDoctorRequest(
        @NotBlank String firstName,
        @NotBlank String lastName,
        @NotBlank String identification,
        @NotBlank String phone,
        @NotNull List<Specialty> specialty,
        @NotNull LocalDate laborStart,
        LocalDate laborEnd,
        @Positive int appointmentInterval,
        List<CreateScheduleRequest> schedules,
        String email,
        @NotBlank String password
) {}