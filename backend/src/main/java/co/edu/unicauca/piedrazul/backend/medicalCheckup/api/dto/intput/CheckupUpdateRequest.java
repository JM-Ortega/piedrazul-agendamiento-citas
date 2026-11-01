package co.edu.unicauca.piedrazul.backend.medicalCheckup.api.dto.intput;

import co.edu.unicauca.piedrazul.backend.jackson.sanitization.Sanitize;

public record CheckupUpdateRequest(
        @Sanitize String description
) {}
