package co.edu.unicauca.piedrazul.backend.user.api.dto.input;

import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.internal.CreateDoctorRequest;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.internal.CreatePatientUserRequest;
import co.edu.unicauca.piedrazul.backend.shared.enums.Role;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

// Se usa tanto en llamadas del front como en llamadas internas
public record CreateSystemUserPayload(
        @Valid
        @NotNull(message = "La informacion para crear el usuario debe ser proporcinoada")
        CreateSystemUserRequest user,

        @Valid
        CreateDoctorRequest doctor,

        @Valid
        CreatePatientUserRequest patient,

        @NotEmpty
        List<Role> roles
) {
}