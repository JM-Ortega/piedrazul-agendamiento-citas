package co.edu.unicauca.piedrazul.backend.audit.domain;


import java.util.List;

/**
 * Resultado paginado, propio del dominio, no depende de Spring Data.
 */
public record AuditEventPage(
        List<AuditEvent> content,
        int page,
        int size,
        long totalElements
) {
    public int totalPages() {
        return size == 0 ? 0 : (int) Math.ceil((double) totalElements / size);
    }
}
