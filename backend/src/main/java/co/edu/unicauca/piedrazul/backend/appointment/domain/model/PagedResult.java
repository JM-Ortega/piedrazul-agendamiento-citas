package co.edu.unicauca.piedrazul.backend.appointment.domain.model;

import java.util.List;

public record PagedResult<T>(
        List<T> content,
        int pageNumber,
        int pageSize,
        long totalElements,
        int totalPages
) {
}
