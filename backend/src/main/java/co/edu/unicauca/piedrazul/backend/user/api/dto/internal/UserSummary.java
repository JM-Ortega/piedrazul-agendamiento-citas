package co.edu.unicauca.piedrazul.backend.user.api.dto.internal;


import java.util.UUID;

public record UserSummary(
        UUID id,
        String username,
        String firstName,
        String lastName,
        String email
) {
}