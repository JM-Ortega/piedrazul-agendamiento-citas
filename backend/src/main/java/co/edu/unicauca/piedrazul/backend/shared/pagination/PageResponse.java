package co.edu.unicauca.piedrazul.backend.shared.pagination;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.PagedResult;
import org.springframework.data.domain.Page;

import java.util.List;

public record PageResponse<T>(
        List<T> content,
        int pageNumber,
        int pageSize,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last,
        boolean empty
) {

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getTotalPages(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.getNumber() == 0,
                page.getNumber() >= page.getTotalPages() -1,
                page.getContent().isEmpty()
        );
    }

    // Overload para módulos cuyo dominio no expone Page<T> de Spring Data directamente.
    public static <T> PageResponse<T> of(List<T> content, int pageNumber, int pageSize, long totalElements,
                                         int totalPages, boolean first, boolean last, boolean empty) {
        return new PageResponse<>(content, pageNumber, pageSize, totalElements, totalPages, first, last, empty);
    }
}
