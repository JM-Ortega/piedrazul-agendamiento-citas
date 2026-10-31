package co.edu.unicauca.piedrazul.backend.appointment.exception;

import org.springframework.http.HttpStatus;

public class InvalidStatisticsDateException extends AppointmentBusinessException {
    public InvalidStatisticsDateException() {
        super("La fecha es obligatoria para estadísticas", "INVALID_STATISTICS_DATE", HttpStatus.BAD_REQUEST);
    }
}
