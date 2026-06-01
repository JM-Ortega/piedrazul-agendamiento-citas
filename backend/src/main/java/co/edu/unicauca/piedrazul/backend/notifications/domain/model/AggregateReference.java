package co.edu.unicauca.piedrazul.backend.notifications.domain.model;

import java.util.UUID;

public record AggregateReference(
        AggregateType type,
        UUID id
) {
}