package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.input;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentState;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.PageQuery;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
public class ListAppointmentFiltersRequest {
    private UUID idDoctor;
    private UUID idPatient;
    private LocalDate date;
    private AppointmentState state;
    private int page = 0;      // Valor por defecto
    private int size = 5;     // Valor por defecto
    private String sortBy = "date";
    private String sortDirection = "DESC";

    // Lista blanca de campos permitidos para ordenamiento
    private static final List<String> ALLOWED_SORT_FIELDS = List.of(
            "date", "appointmentState", "idDoctor", "idPatient"
    );

    public PageQuery toPageQuery() {
        int validatedPage = Math.max(page, 0);
        int validatedSize = Math.clamp(size, 1, 100);
        String validSortBy = isValidSortField(sortBy) ? sortBy : "date";
        boolean ascending = "ASC".equalsIgnoreCase(sortDirection);

        return new PageQuery(validatedPage, validatedSize, validSortBy, ascending);
    }

    private boolean isValidSortField(String field) {
        return field != null && ALLOWED_SORT_FIELDS.contains(field);
    }
    
}
