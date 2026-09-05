package co.edu.unicauca.piedrazul.backend.shared.enums;

import java.time.DayOfWeek;

public enum Workday {
    LUNES,
    MARTES,
    MIERCOLES,
    JUEVES,
    VIERNES;

    public DayOfWeek toDayOfWeek() {
        return switch (this) {
            case LUNES -> DayOfWeek.MONDAY;
            case MARTES -> DayOfWeek.TUESDAY;
            case MIERCOLES -> DayOfWeek.WEDNESDAY;
            case JUEVES -> DayOfWeek.THURSDAY;
            case VIERNES -> DayOfWeek.FRIDAY;
        };
    }
}
