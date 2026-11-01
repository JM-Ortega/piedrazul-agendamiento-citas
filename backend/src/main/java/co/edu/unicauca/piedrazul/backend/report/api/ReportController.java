package co.edu.unicauca.piedrazul.backend.report.api;

import co.edu.unicauca.piedrazul.backend.report.application.ReportService;
import co.edu.unicauca.piedrazul.backend.report.dtos.ExportRequestDto;
import co.edu.unicauca.piedrazul.backend.report.dtos.*;
import co.edu.unicauca.piedrazul.backend.report.dtos.output.AvailabilityResponseDto;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@Tag(name = "Reportes", description = "Operaciones de generación y exportación de reportes de citas y agendas")
@RestController
@RequestMapping("/api/reports")
@PreAuthorize("hasAnyRole('SCHEDULER', 'DOCTOR')")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping("/scheduler/availability")
    @Operation(summary = "Consultar disponibilidad de agenda", description = "Devuelve las métricas de disponibilidad y ocupación para una fecha específica.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Disponibilidad obtenida correctamente"),
            @ApiResponse(responseCode = "400", description = "Fecha proporcionada inválida"),
            @ApiResponse(responseCode = "401", description = "No autenticado"),
            @ApiResponse(responseCode = "403", description = "No tiene permisos para consultar reportes")
    })
    public ResponseEntity<AvailabilityResponseDto> checkAvailability(
            @Parameter(description = "Fecha a consultar en formato yyyy-MM-dd")
            @RequestParam LocalDate date) {
        return ResponseEntity.ok(reportService.checkAvailability(date));
    }

    @PostMapping("/appointments/export")
    @Operation(summary = "Exportar histórico de citas para un doctor",
            description = "Genera y descarga un archivo (Excel, PDF o CSV) con el reporte de citas," +
                    " incluyendo unicamente las columnas seleccioandas.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Archivo generado y listo para descarga"),
            @ApiResponse(responseCode = "400", description = "Parámetros de exportación inválidos"),
            @ApiResponse(responseCode = "401", description = "No autenticado"),
            @ApiResponse(responseCode = "403", description = "No tiene permisos para exportar reportes")
    })
    public ResponseEntity<byte[]> export(
            @Parameter(description = "Id del doctor, fromato, lista de columnas y estado")
            @RequestBody @Valid ExportRequestDto request) {

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

    @PostMapping("/scheduler/export")
    @Operation(summary = "Exportar agenda diaria para todos los doctores", description = "Genera y descarga un archivo (Excel, PDF o CSV) con la agenda de citas consolidada para una fecha específica.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Archivo generado y listo para descarga"),
            @ApiResponse(responseCode = "400", description = "Parámetros de exportación inválidos"),
            @ApiResponse(responseCode = "401", description = "No autenticado"),
            @ApiResponse(responseCode = "403", description = "No tiene permisos para exportar agendas")
    })
    public ResponseEntity<byte[]> exportScheduler(
            @Parameter(description = "Fecha de la agenda y formato deseado para el reporte")
            @RequestBody @Valid SchedulerRequestDto request) {

        byte[] archivo = reportService.exportScheduler(request);

        String contentType;
        String extension;

        switch (request.format()) {
            case EXCEL -> { contentType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"; extension = ".xlsx"; }
            case PDF   -> { contentType = "application/pdf"; extension = ".pdf"; }
            default    -> { contentType = "text/csv; charset=UTF-8"; extension = ".csv"; }
        }
        String filename  = "agenda-" + request.date() + extension;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(contentType));
        headers.setContentDispositionFormData("attachment", filename);
        headers.setContentLength(archivo.length);

        return ResponseEntity.ok().headers(headers).body(archivo);
    }
}