package co.edu.unicauca.piedrazul.backend.appointment.exception;

import org.springframework.http.HttpStatus;

public class InvalidStatisticsYearException extends AppointmentBusinessException {
    public InvalidStatisticsYearException(int year) {
        super("Año inválido para estadísticas: " + year, "INVALID_STATISTICS_YEAR", HttpStatus.BAD_REQUEST);
    }
}
