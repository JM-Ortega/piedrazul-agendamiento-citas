package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.PagedResult;
import lombok.*;
import org.springframework.data.domain.Page;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PageResponse<T> {
    private List<T> content;
    private int pageNumber;
    private int pageSize;
    private long totalElements;
    private int totalPages;
    private boolean first;
    private boolean last;
    private boolean empty;

    // Método estático para crear desde Page de Spring
    public static <T> PageResponse<T> from(PagedResult<?> pagedResult, List<T> content) {

        boolean isFirst = pagedResult.pageNumber() == 0;
        boolean isLast = pagedResult.pageNumber() >= pagedResult.totalPages() - 1;

        return PageResponse.<T>builder()
                .content(content)
                .pageNumber(pagedResult.pageNumber())
                .pageSize(pagedResult.pageSize())
                .totalElements(pagedResult.totalElements())
                .totalPages(pagedResult.totalPages())
                .first(isFirst)
                .last(isLast)
                .empty(content.isEmpty())
                .build();
    }

}
