package co.edu.unicauca.piedrazul.backend.audit.application;

import co.edu.unicauca.piedrazul.backend.audit.exception.InvalidAuditCriteriaException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditEventCriteriaTest {

    private static final Instant NOW = Instant.parse("2026-09-26T12:00:00Z");

    private static AuditEventCriteria between(Instant from, Instant to) {
        return new AuditEventCriteria(from, to, null, null, null, null, null, null, null, 0, 20);
    }

    private static AuditEventCriteria withModule(String moduleCode) {
        return new AuditEventCriteria(null, null, null, moduleCode, null, null, null, null, null, 0, 20);
    }

    private static AuditEventCriteria searching(String search) {
        return new AuditEventCriteria(null, null, null, null, null, search, null, null, null, 0, 20);
    }

    @Test
    void noFilterAtAllIsValid() {
        assertThatCode(() -> between(null, null)).doesNotThrowAnyException();
    }

    @Test
    void aSingleBoundIsValidAndIsNotSubjectToTheRangeLimit() {
        assertThatCode(() -> between(NOW.minus(Duration.ofDays(3000)), null)).doesNotThrowAnyException();
        assertThatCode(() -> between(null, NOW)).doesNotThrowAnyException();
    }

    @Test
    void theRangeCanBeExactlyNinetyDays() {
        assertThatCode(() -> between(NOW.minus(Duration.ofDays(90)), NOW)).doesNotThrowAnyException();
    }

    @Test
    void aRangeLongerThanNinetyDaysIsRejected() {
        assertThatThrownBy(() -> between(NOW.minus(Duration.ofDays(90)).minusSeconds(1), NOW))
                .isInstanceOf(InvalidAuditCriteriaException.class)
                .hasMessageContaining("90");
    }

    @Test
    void aStartAfterTheEndIsRejected() {
        assertThatThrownBy(() -> between(NOW, NOW.minusSeconds(1)))
                .isInstanceOf(InvalidAuditCriteriaException.class)
                .hasMessageContaining("posterior");
    }

    @Test
    void theSearchTermIsTrimmedAndItsSpacesCollapsed() {
        assertThat(searching("  ana    ruiz  ").search()).isEqualTo("ana ruiz");
    }

    @Test
    void aBlankSearchMeansNoSearch() {
        assertThat(searching("   ").search()).isNull();
        assertThat(searching(null).search()).isNull();
    }

    @Test
    void anOverlongSearchIsRejected() {
        assertThatThrownBy(() -> searching("a".repeat(101))).isInstanceOf(InvalidAuditCriteriaException.class);
        assertThatCode(() -> searching("a".repeat(100))).doesNotThrowAnyException();
    }

    @Test
    void blankExactFiltersAreTreatedAsAbsent() {
        var criteria = new AuditEventCriteria(null, null, null, " ", null, null, " ", "", "  ", 0, 20);

        assertThat(criteria.moduleCode()).isNull();
        assertThat(criteria.actorId()).isNull();
        assertThat(criteria.targetEntityType()).isNull();
        assertThat(criteria.targetEntityId()).isNull();
    }

    @Test
    void aBlankModuleCodeMeansNoFilter() {
        assertThat(withModule("   ").moduleCode()).isNull();
        assertThat(withModule(null).moduleCode()).isNull();
        assertThat(withModule("PACIENTES").moduleCode()).isEqualTo("PACIENTES");
    }

    @Test
    void thePageAndSizeAreKeptWithinSafeBounds() {
        assertThat(new AuditEventCriteria(null, null, null, null, null, null, null, null, null, -3, 20).page()).isZero();
        assertThat(new AuditEventCriteria(null, null, null, null, null, null, null, null, null, 0, 0).size()).isEqualTo(50);
        assertThat(new AuditEventCriteria(null, null, null, null, null, null, null, null, null, 0, 5000).size()).isEqualTo(50);
        assertThat(new AuditEventCriteria(null, null, null, null, null, null, null, null, null, 2, 200).size()).isEqualTo(200);
    }
}
