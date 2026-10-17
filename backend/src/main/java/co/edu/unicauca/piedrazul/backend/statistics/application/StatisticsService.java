package co.edu.unicauca.piedrazul.backend.statistics.application;

import co.edu.unicauca.piedrazul.backend.statistics.domain.StatisticsAssembler;
import co.edu.unicauca.piedrazul.backend.statistics.dtos.output.CancellationRateDto;
import co.edu.unicauca.piedrazul.backend.statistics.dtos.output.DailyWorkloadDto;
import co.edu.unicauca.piedrazul.backend.statistics.dtos.output.MonthlyBreakdownDto;
import co.edu.unicauca.piedrazul.backend.statistics.dtos.output.MonthlyTotalDto;
import co.edu.unicauca.piedrazul.backend.statistics.exception.InvalidStatisticsMotivoException;
import co.edu.unicauca.piedrazul.backend.statistics.integration.AppointmentStatsClient;
import co.edu.unicauca.piedrazul.backend.statistics.integration.DoctorNamesClient;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

@Service
public class StatisticsService {

    private static final Set<String> ALLOWED_MOTIVOS = Set.of("CANCELADA", "NO_ASISTIO");

    private final AppointmentStatsClient appointmentStatsClient;
    private final DoctorNamesClient doctorNamesClient;
    private final StatisticsAssembler statisticsAssembler;

    public StatisticsService(AppointmentStatsClient appointmentStatsClient,
                             DoctorNamesClient doctorNamesClient,
                             StatisticsAssembler statisticsAssembler) {
        this.appointmentStatsClient = appointmentStatsClient;
        this.doctorNamesClient = doctorNamesClient;
        this.statisticsAssembler = statisticsAssembler;
    }

    public List<Integer> getAvailableYears() {
        return statisticsAssembler.availableYears(LocalDate.now().getYear());
    }

    public List<MonthlyTotalDto> getMonthlyTotals(int year) {
        return statisticsAssembler.monthlyTotals(appointmentStatsClient.getAttendedByMonth(year));
    }

    /** estado: AGENDADA o ATENDIDA (lo valida el módulo appointment). */
    public MonthlyBreakdownDto getByDoctor(int year, String estado) {
        return statisticsAssembler.byDoctor(
                appointmentStatsClient.getCountByDoctor(year, estado),
                doctorNamesClient.getDoctorNames());
    }

    /** estado: AGENDADA o ATENDIDA (lo valida el módulo appointment). */
    public MonthlyBreakdownDto getBySpecialty(int year, String estado) {
        return statisticsAssembler.bySpecialty(appointmentStatsClient.getCountBySpecialty(year, estado));
    }

    public CancellationRateDto getCancellationRate(int year, String motivo) {
        return statisticsAssembler.cancellationRate(
                appointmentStatsClient.getCancellationData(year, normalizeMotivo(motivo)));
    }

    public DailyWorkloadDto getDailyWorkload(LocalDate date) {
        return statisticsAssembler.dailyWorkload(
                appointmentStatsClient.getDailyCountByDoctor(date),
                doctorNamesClient.getActiveDoctorIds(),
                doctorNamesClient.getDoctorNames());
    }

    private String normalizeMotivo(String motivo) {
        String normalized = motivo == null ? "" : motivo.trim().toUpperCase();
        if (!ALLOWED_MOTIVOS.contains(normalized)) {
            throw new InvalidStatisticsMotivoException(motivo);
        }
        return normalized;
    }
}
