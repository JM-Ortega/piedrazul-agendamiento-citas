package co.edu.unicauca.piedrazul.backend.appointment.exception;

import co.edu.unicauca.piedrazul.backend.shared.BusinessException;
import org.springframework.http.HttpStatus;

/**
 * Una transición que el estado actual de la cita no permite, por ejemplo cancelar una cita que otro
 * usuario ya atendió. Su mensaje explica el motivo y es apto para el cliente.
 *
 * <p>Sigue siendo una {@link IllegalStateException} para quienes llaman al dominio, y conserva el
 * contrato HTTP con el que estas transiciones ya se exponen (500, {@code INTERNAL_ERROR}).
 */
public class AppointmentStateConflictException extends IllegalStateException implements BusinessException {

    public AppointmentStateConflictException(String message) {
        super(message);
    }

    @Override
    public String getErrorCode() {
        return "INTERNAL_ERROR";
    }

    @Override
    public HttpStatus getStatus() {
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    @Override
    public String getModule() {
        return "appointment";
    }
}
