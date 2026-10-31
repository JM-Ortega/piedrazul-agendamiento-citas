package co.edu.unicauca.piedrazul.backend.jackson.validation;

public record ValidationResult(
        boolean valid,
        String field,
        String message
) {}
