package co.edu.unicauca.piedrazul.backend.shared.pagination;

import org.springframework.data.domain.Page;

import java.util.List;

public record PageResponse<T>(
        List<T> content,
        int page,
        int totalPages,
        long totalElements
) {

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getTotalPages(),
                page.getTotalElements()
        );
    }

    // Overload para módulos cuyo dominio no expone Page<T> de Spring Data directamente.
    public static <T> PageResponse<T> of(List<T> content, int page, int totalPages, long totalElements) {
        return new PageResponse<>(content, page, totalPages, totalElements);
    }
}
