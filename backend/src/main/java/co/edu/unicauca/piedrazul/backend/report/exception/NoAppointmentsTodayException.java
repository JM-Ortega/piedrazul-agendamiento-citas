package co.edu.unicauca.piedrazul.backend.report.exception;

import java.time.LocalDate;

public class NoAppointmentsTodayException extends RuntimeException {
    public NoAppointmentsTodayException(LocalDate date) {
        super("No hay citas programadas para el día " + date);
    }
}
