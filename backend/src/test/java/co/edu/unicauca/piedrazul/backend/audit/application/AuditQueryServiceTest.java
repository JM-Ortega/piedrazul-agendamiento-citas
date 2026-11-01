package co.edu.unicauca.piedrazul.backend.audit.application;

import co.edu.unicauca.piedrazul.backend.audit.domain.AuditEventPage;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditEventQuery;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Cómo se convierten los días pedidos en el rango de instantes que se consulta. */
class AuditQueryServiceTest {

    private AuditEventRepository repository;
    private AuditQueryService service;

    @BeforeEach
    void setUp() {
        repository = mock(AuditEventRepository.class);
        when(repository.findByCriteria(any())).thenReturn(new AuditEventPage(List.of(), 0, 20, 0));
        service = new AuditQueryService(repository, "America/Bogota");
    }

    private AuditEventQuery searchBetween(String from, String to) {
        service.search(new AuditEventCriteria(
                from == null ? null : LocalDate.parse(from), to == null ? null : LocalDate.parse(to),
                null, null, null, null, null, 0, 20));
        ArgumentCaptor<AuditEventQuery> captor = ArgumentCaptor.forClass(AuditEventQuery.class);
        verify(repository).findByCriteria(captor.capture());
        return captor.getValue();
    }

    @Test
    void aSingleDayCoversFromItsMidnightToTheNextOneInColombianTime() {
        var query = searchBetween("2026-09-26", "2026-09-26");

        assertThat(query.from()).isEqualTo(Instant.parse("2026-09-26T05:00:00Z"));
        assertThat(query.toExclusive()).isEqualTo(Instant.parse("2026-09-27T05:00:00Z"));
    }

    @Test
    void theLastDayIsIncludedCompletely() {
        var query = searchBetween("2026-09-01", "2026-09-30");

        assertThat(query.from()).isEqualTo(Instant.parse("2026-09-01T05:00:00Z"));
        assertThat(query.toExclusive()).isEqualTo(Instant.parse("2026-10-01T05:00:00Z"));
    }

    @Test
    void aMissingDateLeavesThatEndOpen() {
        assertThat(searchBetween(null, "2026-09-26").from()).isNull();
    }

    @Test
    void withoutDatesThereIsNoRange() {
        var query = searchBetween(null, null);

        assertThat(query.from()).isNull();
        assertThat(query.toExclusive()).isNull();
    }
}
