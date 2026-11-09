package co.edu.unicauca.piedrazul.backend.report.exception;

import org.springframework.http.HttpStatus;

import java.time.LocalDate;

public class NoAppointmentsTodayException extends ReportBusinessException {
    public NoAppointmentsTodayException(LocalDate date) {
        super("No hay citas registradas para el día " + date, "NO_APPOINTMENTS_TODAY", HttpStatus.NOT_FOUND);
    }
}
