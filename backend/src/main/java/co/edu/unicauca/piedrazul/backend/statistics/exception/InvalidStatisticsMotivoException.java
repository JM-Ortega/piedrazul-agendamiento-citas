package co.edu.unicauca.piedrazul.backend.statistics.exception;

import org.springframework.http.HttpStatus;

public class InvalidStatisticsMotivoException extends StatisticsBusinessException {
    public InvalidStatisticsMotivoException(String motivo) {
        super("Motivo inválido para estadísticas: " + motivo + ". Valores permitidos: CANCELADA o NO_ASISTIO",
                "INVALID_STATISTICS_MOTIVO", HttpStatus.BAD_REQUEST);
    }
}
