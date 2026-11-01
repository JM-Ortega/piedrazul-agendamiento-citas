package co.edu.unicauca.piedrazul.backend.statistics.application;

import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output.DoctorAppointmentsCountDto;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output.DoctorDailyCountDto;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output.MonthlyCancellationDataDto;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output.MonthlyDoctorRowDto;
import co.edu.unicauca.piedrazul.backend.statistics.domain.StatisticsAssembler;
import co.edu.unicauca.piedrazul.backend.statistics.dtos.output.CancellationRateDto;
import co.edu.unicauca.piedrazul.backend.statistics.dtos.output.DailyWorkloadDto;
import co.edu.unicauca.piedrazul.backend.statistics.dtos.output.MonthlyBreakdownDto;
import co.edu.unicauca.piedrazul.backend.statistics.exception.InvalidStatisticsMotivoException;
import co.edu.unicauca.piedrazul.backend.statistics.integration.AppointmentStatsClient;
import co.edu.unicauca.piedrazul.backend.statistics.integration.DoctorNamesClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StatisticsServiceTest {

    private static final UUID CARLOS = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID LAURA = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Mock
    private AppointmentStatsClient appointmentStatsClient;

    @Mock
    private DoctorNamesClient doctorNamesClient;

    private StatisticsService service;

    @BeforeEach
    void setUp() {
        service = new StatisticsService(appointmentStatsClient, doctorNamesClient, new StatisticsAssembler());
    }

    @Test
    void availableYearsStartsAtNextYear() {
        int nextYear = LocalDate.now().getYear() + 1;

        assertThat(service.getAvailableYears()).first().isEqualTo(nextYear);
        verifyNoInteractions(appointmentStatsClient, doctorNamesClient);
    }

    @Test
    void byDoctorJoinsAppointmentCountsWithDoctorNames() {
        when(appointmentStatsClient.getCountByDoctor(2026, "AGENDADA")).thenReturn(new DoctorAppointmentsCountDto(
                List.of(CARLOS), List.of(new MonthlyDoctorRowDto(0, Map.of(CARLOS, 12)))));
        when(doctorNamesClient.getDoctorNames()).thenReturn(Map.of(CARLOS, "Carlos Ramírez"));

        MonthlyBreakdownDto result = service.getByDoctor(2026, "AGENDADA");

        assertThat(result.series()).containsExactly("Carlos Ramírez");
        assertThat(result.rows().getFirst().values()).containsEntry("Carlos Ramírez", 12);
    }

    @Test
    void cancellationRateNormalizesMotivo() {
        when(appointmentStatsClient.getCancellationData(2026, "NO_ASISTIO"))
                .thenReturn(List.of(new MonthlyCancellationDataDto(0, 10, 1)));

        CancellationRateDto result = service.getCancellationRate(2026, " no_asistio ");

        assertThat(result.yearRate()).isEqualTo(10);
        assertThat(result.yearCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"AGENDADA", "ATENDIDA", "OTRO"})
    void cancellationRateRejectsInvalidMotivo(String motivo) {
        assertThatThrownBy(() -> service.getCancellationRate(2026, motivo))
                .isInstanceOf(InvalidStatisticsMotivoException.class);

        verifyNoInteractions(appointmentStatsClient);
    }

    @Test
    void dailyWorkloadUsesActiveDoctorsAndNames() {
        LocalDate date = LocalDate.of(2026, 10, 10);
        when(appointmentStatsClient.getDailyCountByDoctor(date))
                .thenReturn(List.of(new DoctorDailyCountDto(CARLOS, 9)));
        when(doctorNamesClient.getActiveDoctorIds()).thenReturn(List.of(CARLOS, LAURA));
        when(doctorNamesClient.getDoctorNames()).thenReturn(Map.of(CARLOS, "Carlos Ramírez", LAURA, "Laura Gómez"));

        DailyWorkloadDto result = service.getDailyWorkload(date);

        assertThat(result.dayTotal()).isEqualTo(9);
        assertThat(result.doctors()).hasSize(2);
    }
}
