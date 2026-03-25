package co.edu.unicauca.piedrazul.backend.appointment.domain.model;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppointmentTimeTest {

    // ─────────────────────────────────────────────
    // Construcción válida
    // ─────────────────────────────────────────────

    @Test
    void constructorShouldCreateTimeWhenHourIsAtLowerBound() {
        AppointmentTime time = new AppointmentTime(LocalTime.of(7, 0));
        assertThat(time.getTime()).isEqualTo(LocalTime.of(7, 0));
    }

    @Test
    void constructorShouldCreateTimeWhenHourIsAtUpperBound() {
        AppointmentTime time = new AppointmentTime(LocalTime.of(12, 0));
        assertThat(time.getTime()).isEqualTo(LocalTime.of(12, 0));
    }

    @Test
    void constructorShouldCreateTimeWhenHourIsInValidRange() {
        AppointmentTime time = new AppointmentTime(LocalTime.of(9, 30));
        assertThat(time.getTime()).isEqualTo(LocalTime.of(9, 30));
    }

    // ─────────────────────────────────────────────
    // Construcción inválida
    // ─────────────────────────────────────────────

    @Test
    void constructorShouldThrowWhenTimeIsNull() {
        assertThatThrownBy(() -> new AppointmentTime(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("La hora no puede ser nula");
    }

    @Test
    void constructorShouldThrowWhenHourIsBeforeSevenAm() {
        assertThatThrownBy(() -> new AppointmentTime(LocalTime.of(6, 59)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Piedrazul atiende entre 7:00 am y 12:00 pm");
    }

    @Test
    void constructorShouldThrowWhenHourIsAfterNoon() {
        assertThatThrownBy(() -> new AppointmentTime(LocalTime.of(12, 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Piedrazul atiende entre 7:00 am y 12:00 pm");
    }

    @Test
    void constructorShouldThrowWhenHourIsMidnight() {
        assertThatThrownBy(() -> new AppointmentTime(LocalTime.of(0, 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Piedrazul atiende entre 7:00 am y 12:00 pm");
    }

    // ─────────────────────────────────────────────
    // collidesWith — hay colisión
    // ─────────────────────────────────────────────

    @Test
    void collidesWithShouldReturnTrueWhenTimesAreEqual() {
        AppointmentTime a = new AppointmentTime(LocalTime.of(9, 0));
        AppointmentTime b = new AppointmentTime(LocalTime.of(9, 0));

        assertThat(a.collidesWith(b, 30)).isTrue();
    }

    @Test
    void collidesWithShouldReturnTrueWhenDifferenceIsLessThanInterval() {
        // diferencia de 15 min < intervalo de 30 min → colisión
        AppointmentTime a = new AppointmentTime(LocalTime.of(9, 0));
        AppointmentTime b = new AppointmentTime(LocalTime.of(9, 15));

        assertThat(a.collidesWith(b, 30)).isTrue();
    }

    @Test
    void collidesWithShouldReturnTrueSymmetrically() {
        // La colisión debe ser simétrica: a vs b == b vs a
        AppointmentTime a = new AppointmentTime(LocalTime.of(9, 0));
        AppointmentTime b = new AppointmentTime(LocalTime.of(9, 15));

        assertThat(b.collidesWith(a, 30)).isTrue();
    }

    // ─────────────────────────────────────────────
    // collidesWith — no hay colisión
    // ─────────────────────────────────────────────

    @Test
    void collidesWithShouldReturnFalseWhenDifferenceEqualsInterval() {
        // diferencia exactamente igual al intervalo → NO colisiona (límite exacto)
        AppointmentTime a = new AppointmentTime(LocalTime.of(9, 0));
        AppointmentTime b = new AppointmentTime(LocalTime.of(9, 30));

        assertThat(a.collidesWith(b, 30)).isFalse();
    }

    @Test
    void collidesWithShouldReturnFalseWhenDifferenceIsGreaterThanInterval() {
        // diferencia de 60 min > intervalo de 30 min → libre
        AppointmentTime a = new AppointmentTime(LocalTime.of(8, 0));
        AppointmentTime b = new AppointmentTime(LocalTime.of(9, 0));

        assertThat(a.collidesWith(b, 30)).isFalse();
    }

    @Test
    void collidesWithShouldReturnFalseWithSmallIntervalAndLargeDifference() {
        // intervalo de 15 min, diferencia de 30 min → libre
        AppointmentTime a = new AppointmentTime(LocalTime.of(7, 0));
        AppointmentTime b = new AppointmentTime(LocalTime.of(7, 30));

        assertThat(a.collidesWith(b, 15)).isFalse();
    }

    // ─────────────────────────────────────────────
    // equals y hashCode (ValueObject)
    // ─────────────────────────────────────────────

    @Test
    void equalsShouldReturnTrueForSameTime() {
        AppointmentTime a = new AppointmentTime(LocalTime.of(10, 0));
        AppointmentTime b = new AppointmentTime(LocalTime.of(10, 0));

        assertThat(a).isEqualTo(b);
    }

    @Test
    void equalsShouldReturnFalseForDifferentTime() {
        AppointmentTime a = new AppointmentTime(LocalTime.of(10, 0));
        AppointmentTime b = new AppointmentTime(LocalTime.of(11, 0));

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void hashCodeShouldBeEqualForSameTime() {
        AppointmentTime a = new AppointmentTime(LocalTime.of(10, 0));
        AppointmentTime b = new AppointmentTime(LocalTime.of(10, 0));

        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }
}