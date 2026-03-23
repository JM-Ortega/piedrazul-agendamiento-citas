package co.edu.unicauca.piedrazul.backend.appointment.controller.dtos;

import co.edu.unicauca.piedrazul.backend.appointment.model.models.enumSchedulingOrigin;
import co.edu.unicauca.piedrazul.backend.appointment.model.models.enumSpecialty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

public record appointmentCreateRequest(
        @NotNull UUID idDoctor,
        @NotNull UUID idPatient,
        @NotNull enumSpecialty specialty,
        @NotNull LocalDate date,
        @NotNull LocalTime startTime,
        @NotNull enumSchedulingOrigin schedulingOrigin
) {
}

