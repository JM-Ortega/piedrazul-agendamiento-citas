package co.edu.unicauca.piedrazul.backend.statistics.api;

import co.edu.unicauca.piedrazul.backend.statistics.application.StatisticsService;
import co.edu.unicauca.piedrazul.backend.statistics.dtos.output.CancellationRateDto;
import co.edu.unicauca.piedrazul.backend.statistics.dtos.output.DailyWorkloadDto;
import co.edu.unicauca.piedrazul.backend.statistics.dtos.output.MonthlyBreakdownDto;
import co.edu.unicauca.piedrazul.backend.statistics.dtos.output.MonthlyTotalDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@Tag(name = "Estadísticas", description = "Estadísticas de citas para el panel de administración. Meses: 0 = enero ... 11 = diciembre")
@RestController
@RequestMapping("/api/statistics")
@PreAuthorize("hasRole('ADMIN')")
public class StatisticsController {

    private final StatisticsService statisticsService;

    public StatisticsController(StatisticsService statisticsService) {
        this.statisticsService = statisticsService;
    }

    @GetMapping("/years")
    @Operation(summary = "Años disponibles", description = "Años que se pueden consultar, desde el año siguiente al actual hasta el 2000, en orden descendente.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Años obtenidos correctamente"),
            @ApiResponse(responseCode = "401", description = "No autenticado"),
            @ApiResponse(responseCode = "403", description = "No tiene permisos para consultar estadísticas")
    })
    public ResponseEntity<List<Integer>> getAvailableYears() {
        return ResponseEntity.ok(statisticsService.getAvailableYears());
    }

    @GetMapping("/appointments/monthly")
    @Operation(summary = "Citas atendidas por mes", description = "Total de citas ATENDIDAS de cada uno de los 12 meses del año.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Totales obtenidos correctamente"),
            @ApiResponse(responseCode = "400", description = "Año inválido"),
            @ApiResponse(responseCode = "401", description = "No autenticado"),
            @ApiResponse(responseCode = "403", description = "No tiene permisos para consultar estadísticas")
    })
    public ResponseEntity<List<MonthlyTotalDto>> getMonthlyTotals(
            @Parameter(description = "Año a consultar")
            @RequestParam("year") int year) {
        return ResponseEntity.ok(statisticsService.getMonthlyTotals(year));
    }

    @GetMapping("/appointments/by-doctor")
    @Operation(summary = "Citas por médico", description = "Citas de cada médico por mes. AGENDADA cuenta todas las citas; ATENDIDA, solo las atendidas.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Conteos obtenidos correctamente"),
            @ApiResponse(responseCode = "400", description = "Año o estado inválido"),
            @ApiResponse(responseCode = "401", description = "No autenticado"),
            @ApiResponse(responseCode = "403", description = "No tiene permisos para consultar estadísticas")
    })
    public ResponseEntity<MonthlyBreakdownDto> getByDoctor(
            @Parameter(description = "Año a consultar")
            @RequestParam("year") int year,
            @Parameter(description = "AGENDADA o ATENDIDA")
            @RequestParam("estado") String estado) {
        return ResponseEntity.ok(statisticsService.getByDoctor(year, estado));
    }

    @GetMapping("/appointments/by-specialty")
    @Operation(summary = "Citas por especialidad", description = "Citas de cada especialidad por mes. AGENDADA cuenta todas las citas; ATENDIDA, solo las atendidas.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Conteos obtenidos correctamente"),
            @ApiResponse(responseCode = "400", description = "Año o estado inválido"),
            @ApiResponse(responseCode = "401", description = "No autenticado"),
            @ApiResponse(responseCode = "403", description = "No tiene permisos para consultar estadísticas")
    })
    public ResponseEntity<MonthlyBreakdownDto> getBySpecialty(
            @Parameter(description = "Año a consultar")
            @RequestParam("year") int year,
            @Parameter(description = "AGENDADA o ATENDIDA")
            @RequestParam("estado") String estado) {
        return ResponseEntity.ok(statisticsService.getBySpecialty(year, estado));
    }

    @GetMapping("/appointments/cancellation-rate")
    @Operation(summary = "Tasa de no asistencia", description = "Porcentaje y cantidad de citas del motivo elegido, por mes y del año completo.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Tasas obtenidas correctamente"),
            @ApiResponse(responseCode = "400", description = "Año o motivo inválido"),
            @ApiResponse(responseCode = "401", description = "No autenticado"),
            @ApiResponse(responseCode = "403", description = "No tiene permisos para consultar estadísticas")
    })
    public ResponseEntity<CancellationRateDto> getCancellationRate(
            @Parameter(description = "Año a consultar")
            @RequestParam("year") int year,
            @Parameter(description = "CANCELADA o NO_ASISTIO")
            @RequestParam("motivo") String motivo) {
        return ResponseEntity.ok(statisticsService.getCancellationRate(year, motivo));
    }

    @GetMapping("/appointments/daily-workload")
    @Operation(summary = "Carga de trabajo diaria", description = "Citas de cada médico en la fecha (sin contar canceladas), total del día, promedio y nivel de carga.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Carga obtenida correctamente"),
            @ApiResponse(responseCode = "400", description = "Fecha inválida"),
            @ApiResponse(responseCode = "401", description = "No autenticado"),
            @ApiResponse(responseCode = "403", description = "No tiene permisos para consultar estadísticas")
    })
    public ResponseEntity<DailyWorkloadDto> getDailyWorkload(
            @Parameter(description = "Fecha en formato yyyy-MM-dd")
            @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(statisticsService.getDailyWorkload(date));
    }
}
