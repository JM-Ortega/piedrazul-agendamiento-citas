package co.edu.unicauca.piedrazul.backend.appointment.domain.model;

public record PageQuery(
        int page,
        int size,
        String sortBy,
        boolean ascending
) {
}