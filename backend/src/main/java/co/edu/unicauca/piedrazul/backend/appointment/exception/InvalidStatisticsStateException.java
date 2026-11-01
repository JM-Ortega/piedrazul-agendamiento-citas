package co.edu.unicauca.piedrazul.backend.appointment.exception;

import org.springframework.http.HttpStatus;

public class InvalidStatisticsStateException extends AppointmentBusinessException {
    public InvalidStatisticsStateException(String state) {
        super("Estado inválido para estadísticas: " + state, "INVALID_STATISTICS_STATE", HttpStatus.BAD_REQUEST);
    }
}
