package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.input;

import co.edu.unicauca.piedrazul.backend.jackson.sanitization.Sanitize;

public record ClinicalHistoryDescription(@Sanitize String description) {
}
