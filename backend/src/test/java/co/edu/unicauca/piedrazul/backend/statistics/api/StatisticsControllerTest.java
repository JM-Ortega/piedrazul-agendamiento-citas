package co.edu.unicauca.piedrazul.backend.statistics.api;

import co.edu.unicauca.piedrazul.backend.appointment.exception.InvalidStatisticsYearException;
import co.edu.unicauca.piedrazul.backend.statistics.application.StatisticsService;
import co.edu.unicauca.piedrazul.backend.statistics.dtos.output.DailyWorkloadDto;
import co.edu.unicauca.piedrazul.backend.statistics.exception.InvalidStatisticsMotivoException;
import co.edu.unicauca.piedrazul.backend.statistics.exception.StatisticsExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Rutas, parámetros y respuestas de error del controller. La autorización por rol
 * (@PreAuthorize) no se evalúa en este montaje standalone.
 */
class StatisticsControllerTest {

    private StatisticsService statisticsService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        statisticsService = mock(StatisticsService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new StatisticsController(statisticsService))
                .setControllerAdvice(new StatisticsExceptionHandler())
                .build();
    }

    @Test
    void yearsReturnsList() throws Exception {
        when(statisticsService.getAvailableYears()).thenReturn(List.of(2027, 2026));

        mockMvc.perform(get("/api/statistics/years"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value(2027))
                .andExpect(jsonPath("$[1]").value(2026));
    }

    @Test
    void dailyWorkloadParsesIsoDate() throws Exception {
        LocalDate date = LocalDate.of(2026, 10, 10);
        when(statisticsService.getDailyWorkload(date)).thenReturn(new DailyWorkloadDto(0, 0, List.of()));

        mockMvc.perform(get("/api/statistics/appointments/daily-workload").param("date", "2026-10-10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dayTotal").value(0));

        verify(statisticsService).getDailyWorkload(date);
    }

    @Test
    void missingParameterReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/statistics/appointments/monthly"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void nonNumericYearReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/statistics/appointments/monthly").param("year", "abc"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidDateReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/statistics/appointments/daily-workload").param("date", "10/10/2026"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidMotivoReturnsBadRequest() throws Exception {
        when(statisticsService.getCancellationRate(2026, "OTRO"))
                .thenThrow(new InvalidStatisticsMotivoException("OTRO"));

        mockMvc.perform(get("/api/statistics/appointments/cancellation-rate")
                        .param("year", "2026").param("motivo", "OTRO"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void appointmentBusinessErrorReturnsBadRequestInsteadOfServerError() throws Exception {
        when(statisticsService.getMonthlyTotals(1990)).thenThrow(new InvalidStatisticsYearException(1990));

        mockMvc.perform(get("/api/statistics/appointments/monthly").param("year", "1990"))
                .andExpect(status().isBadRequest());
    }
}
