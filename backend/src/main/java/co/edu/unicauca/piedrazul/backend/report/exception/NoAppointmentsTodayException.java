package co.edu.unicauca.piedrazul.backend.report.exception;

public class NoAppointmentsTodayException extends RuntimeException {
    public NoAppointmentsTodayException(String message) {
        super(message);
    }
}
