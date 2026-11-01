package co.edu.unicauca.piedrazul.backend.doctors.domain;

import co.edu.unicauca.piedrazul.backend.doctors.exception.DateConflictException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DoctorTimeOffTest {

    private final LocalDate today = LocalDate.of(2026, 10, 3);

    private DoctorTimeOff timeOff(LocalDate start, LocalDate end) {
        return new DoctorTimeOff(UUID.randomUUID(), start, end, null);
    }

    @Test
    void shouldRejectEndBeforeStart() {
        assertThatThrownBy(() -> timeOff(today.plusDays(2), today.plusDays(1)))
                .isInstanceOf(DateConflictException.class);
    }

    @Test
    void coversShouldBeInclusiveOnBothEnds() {
        DoctorTimeOff t = timeOff(today, today.plusDays(5));

        assertThat(t.covers(today.minusDays(1))).isFalse();
        assertThat(t.covers(today)).isTrue();
        assertThat(t.covers(today.plusDays(5))).isTrue();
        assertThat(t.covers(today.plusDays(6))).isFalse();
    }

    @Test
    void overlapsShouldDetectTouchingRanges() {
        DoctorTimeOff t = timeOff(today, today.plusDays(5));

        assertThat(t.overlaps(today.plusDays(5), today.plusDays(8))).isTrue();
        assertThat(t.overlaps(today.minusDays(3), today)).isTrue();
        assertThat(t.overlaps(today.plusDays(6), today.plusDays(8))).isFalse();
    }

    @Test
    void statusShouldDependOnToday() {
        assertThat(timeOff(today.plusDays(1), today.plusDays(3)).statusOn(today)).isEqualTo(TimeOffStatus.PROGRAMADO);
        assertThat(timeOff(today, today.plusDays(3)).statusOn(today)).isEqualTo(TimeOffStatus.PROGRAMADO);
        assertThat(timeOff(today.minusDays(2), today).statusOn(today)).isEqualTo(TimeOffStatus.EN_CURSO);
        assertThat(timeOff(today.minusDays(5), today.minusDays(1)).statusOn(today)).isEqualTo(TimeOffStatus.FINALIZADO);
    }

    @Test
    void endBeforeShouldFreeTodayOnwards() {
        DoctorTimeOff t = timeOff(today.minusDays(2), today.plusDays(3));

        t.endBefore(today);

        assertThat(t.getEndDate()).isEqualTo(today.minusDays(1));
        assertThat(t.covers(today)).isFalse();
        assertThat(t.covers(today.minusDays(1))).isTrue();
    }

    @Test
    void blankReasonShouldBeStoredAsNull() {
        assertThat(new DoctorTimeOff(UUID.randomUUID(), today, today, "   ").getReason()).isNull();
    }
}
