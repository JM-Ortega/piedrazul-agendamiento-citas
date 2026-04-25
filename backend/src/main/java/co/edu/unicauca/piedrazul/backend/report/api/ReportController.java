package co.edu.unicauca.piedrazul.backend.report.api;

import co.edu.unicauca.piedrazul.backend.report.application.ReportService;
import co.edu.unicauca.piedrazul.backend.report.exception.NoAppointmentsTodayException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping("/appointments/csv")
    public ResponseEntity<byte[]> exportDailyCsv(@RequestParam UUID idDoctor) {
        byte[] csv = reportService.exportDailyAppointmentsCSV(idDoctor);

        String filename = "citas-" + LocalDate.now() + ".csv";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv; charset=UTF-8"));
        headers.setContentDispositionFormData("attachment", filename);
        headers.setContentLength(csv.length);

        return ResponseEntity.ok().headers(headers).body(csv);
    }

    @ExceptionHandler(NoAppointmentsTodayException.class)
    public ResponseEntity<Void> handleNoAppointments() {
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }





}
