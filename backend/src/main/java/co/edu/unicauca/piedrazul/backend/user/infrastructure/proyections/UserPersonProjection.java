package co.edu.unicauca.piedrazul.backend.user.infrastructure.proyections;

import java.util.UUID;

public record UserPersonProjection(
        UUID userId,
        UUID personId
) {}
