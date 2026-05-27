package co.edu.unicauca.piedrazul.backend.user.api.dto.output;

import co.edu.unicauca.piedrazul.backend.admin.api.dto.DoctorUserDataResponse;

import java.util.List;
import java.util.UUID;

public record SystemUserResponse(
        UUID id,
        String firstName,
        String lastName,
        String documentId,
        List<String> roles
) {}