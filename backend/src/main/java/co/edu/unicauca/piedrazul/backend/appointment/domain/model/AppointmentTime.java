package co.edu.unicauca.piedrazul.backend.appointment.domain.model;

import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

public final class AppointmentTime {

    private static final LocalTime OPENING_TIME = LocalTime.of(7, 0);
    private static final LocalTime CLOSING_TIME = LocalTime.of(12, 0);

    private final LocalTime time;

    // Constructor público — EXACTAMENTE igual que antes, ningún llamador existente se entera del cambio
    public AppointmentTime(LocalTime time) {
        this(time, true);
    }

    // Único punto de entrada para saltarse la validación, y solo a propósito
    public static AppointmentTime withoutBusinessHoursRestriction(LocalTime time) {
        return new AppointmentTime(time, false);
    }

    private AppointmentTime(LocalTime time, boolean enforceBusinessHours) {
        Objects.requireNonNull(time, "La hora no puede ser nula");
        if (enforceBusinessHours && (time.isBefore(OPENING_TIME) || time.isAfter(CLOSING_TIME))) {
            throw new IllegalArgumentException("Piedrazul atiende entre 7:00 am y 12:00 pm");
        }
        this.time = time;
    }

    public boolean collidesWith(AppointmentTime other, int intervalMinutes) {
        long difference = Math.abs(ChronoUnit.MINUTES.between(this.time, other.time));
        return difference < intervalMinutes;
    }

    public LocalTime getTime() { return time; }

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