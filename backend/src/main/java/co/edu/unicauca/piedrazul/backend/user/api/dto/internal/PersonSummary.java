package co.edu.unicauca.piedrazul.backend.user.api.dto.internal;

import co.edu.unicauca.piedrazul.backend.shared.enums.IdentificationType;

import java.util.UUID;

public record PersonSummary(
        UUID id,
        UUID userId,
        IdentificationType identificationType,
        String identification,
        String firstName,
        String lastName,
        String phone,
        String email
) {
}
