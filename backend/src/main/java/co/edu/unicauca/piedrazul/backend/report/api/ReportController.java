package co.edu.unicauca.piedrazul.backend.report.api;

import co.edu.unicauca.piedrazul.backend.report.application.ReportService;
import co.edu.unicauca.piedrazul.backend.report.dtos.ExportRequestDto;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/reports")
@PreAuthorize("hasAnyRole('SCHEDULER', 'DOCTOR')")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }


    @PostMapping("/appointments/export")
    public ResponseEntity<byte[]> export(@RequestBody @Valid ExportRequestDto request) {
        byte[] archivo = reportService.export(request);

        String contentType;
        String extension;


        switch (request.format()) {
            case EXCEL -> { contentType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"; extension = ".xlsx"; }
            case PDF   -> { contentType = "application/pdf"; extension = ".pdf"; }
            default    -> { contentType = "text/csv; charset=UTF-8"; extension = ".csv"; }
        }

        String filename = "citas-" + LocalDate.now() + extension;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(contentType));
        headers.setContentDispositionFormData("attachment", filename);
        headers.setContentLength(archivo.length);

        return ResponseEntity.ok().headers(headers).body(archivo);
    }
}