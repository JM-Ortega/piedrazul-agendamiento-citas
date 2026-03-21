package co.edu.unicauca.piedrazul.backend.appointment.domain.model;

import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

public final class AppointmentTime {

    private final LocalTime time;

    public AppointmentTime(LocalTime time) {
        Objects.requireNonNull(time, "La hora no puede ser nula");

        // Piedrazul solo atiende en la mañana
        if (time.isBefore(LocalTime.of(7, 0)) || time.isAfter(LocalTime.of(12, 0))) {
            throw new IllegalArgumentException(
                    "Piedrazul atiende entre 7:00 am y 12:00 pm"
            );
        }
        this.time = time;
    }

    public boolean collidesWith(AppointmentTime other, int intervalMinutes) {
        long difference = Math.abs(
                ChronoUnit.MINUTES.between(this.time, other.time)
        );
        return difference < intervalMinutes;
    }

    public LocalTime getTime() { return time; }

    // Sin esto, dos AppointmentTime con la misma hora serían "distintos" para Java
    // Porque los VOs se comparan por valor y no por referencia
    @Override
    public boolean equals(Object o) {
        if (!(o instanceof AppointmentTime other)) return false;
        return time.equals(other.time);
    }
    @Override
    public int hashCode() {
        return time.hashCode();
    }
}
