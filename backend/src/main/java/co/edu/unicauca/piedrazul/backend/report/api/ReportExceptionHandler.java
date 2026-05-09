package co.edu.unicauca.piedrazul.backend.report.api;

import co.edu.unicauca.piedrazul.backend.report.exception.NoAppointmentsTodayException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = ReportController.class)
public class ReportExceptionHandler {

    @ExceptionHandler(NoAppointmentsTodayException.class)
    public ResponseEntity<Void> handleNoAppointments() {
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
}