package co.edu.unicauca.piedrazul.backend.user.api.dto.output;

import java.util.List;
import java.util.UUID;

public record SystemUserResponse(
        UUID id,
        String firstName,
        String lastName,
        String documentId,
        List<String> roles
) {}