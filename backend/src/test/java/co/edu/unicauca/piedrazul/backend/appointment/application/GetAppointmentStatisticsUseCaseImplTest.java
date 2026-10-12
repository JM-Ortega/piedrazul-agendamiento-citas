package co.edu.unicauca.piedrazul.backend.appointment.application;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentState;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.statistics.DoctorDailyCount;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.statistics.MonthlyBreakdown;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.statistics.MonthlyGroupCount;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.statistics.MonthlyStateCount;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.statistics.MonthlyStateCounts;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.statistics.StatisticDimension;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentRepository;
import co.edu.unicauca.piedrazul.backend.appointment.exception.InvalidStatisticsDateException;
import co.edu.unicauca.piedrazul.backend.appointment.exception.InvalidStatisticsStateException;
import co.edu.unicauca.piedrazul.backend.appointment.exception.InvalidStatisticsYearException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetAppointmentStatisticsUseCaseImplTest {

    private static final List<AppointmentState> ALL_STATES = List.of(AppointmentState.values());

    @Mock
    private AppointmentRepository appointmentRepository;

    private GetAppointmentStatisticsUseCaseImpl useCase;

    @BeforeEach
    void setUp() {
        useCase = new GetAppointmentStatisticsUseCaseImpl(appointmentRepository);
    }

    @Nested
    class YearValidationTests {

        @ParameterizedTest
        @ValueSource(ints = {1999, 2101})
        void getMonthlyBreakdownShouldThrowWhenYearIsOutOfRange(int year) {
            assertThatThrownBy(() -> useCase.getMonthlyBreakdown(
                    year, StatisticDimension.DOCTOR, AppointmentState.AGENDADA))
                    .isInstanceOf(InvalidStatisticsYearException.class);

            verifyNoInteractions(appointmentRepository);
        }

        @ParameterizedTest
        @ValueSource(ints = {2000, 2100})
        void getMonthlyBreakdownShouldAcceptBoundaryYears(int year) {
            when(appointmentRepository.countByMonthAndDoctor(year, ALL_STATES)).thenReturn(List.of());

            MonthlyBreakdown result = useCase.getMonthlyBreakdown(
                    year, StatisticDimension.DOCTOR, AppointmentState.AGENDADA);

            assertThat(result.rows()).hasSize(12);
        }

        @ParameterizedTest
        @ValueSource(ints = {1999, 2101})
        void getMonthlyStateCountsShouldThrowWhenYearIsOutOfRange(int year) {
            assertThatThrownBy(() -> useCase.getMonthlyStateCounts(year))
                    .isInstanceOf(InvalidStatisticsYearException.class);

            verifyNoInteractions(appointmentRepository);
        }

        @ParameterizedTest
        @ValueSource(ints = {2000, 2100})
        void getMonthlyStateCountsShouldAcceptBoundaryYears(int year) {
            when(appointmentRepository.countByMonthAndState(year)).thenReturn(List.of());

            assertThat(useCase.getMonthlyStateCounts(year)).hasSize(12);
        }
    }

    @Nested
    class StateValidationTests {

        @Test
        void shouldThrowWhenStateIsNull() {
            assertThatThrownBy(() -> useCase.getMonthlyBreakdown(2026, StatisticDimension.DOCTOR, null))
                    .isInstanceOf(InvalidStatisticsStateException.class);

            verifyNoInteractions(appointmentRepository);
        }

        @ParameterizedTest
        @ValueSource(strings = {"CANCELADA", "NO_ASISTIO", "REPROGRAMADA"})
        void shouldThrowWhenStateIsNeitherAgendadaNorAtendida(String state) {
            AppointmentState invalid = AppointmentState.valueOf(state);

            assertThatThrownBy(() -> useCase.getMonthlyBreakdown(2026, StatisticDimension.SPECIALTY, invalid))
                    .isInstanceOf(InvalidStatisticsStateException.class);

            verifyNoInteractions(appointmentRepository);
        }

        @Test
        void atendidaShouldQueryOnlyAtendidaState() {
            when(appointmentRepository.countByMonthAndDoctor(2026, List.of(AppointmentState.ATENDIDA)))
                    .thenReturn(List.of());

            useCase.getMonthlyBreakdown(2026, StatisticDimension.DOCTOR, AppointmentState.ATENDIDA);

            verify(appointmentRepository).countByMonthAndDoctor(2026, List.of(AppointmentState.ATENDIDA));
        }

        @Test
        void agendadaShouldQueryAllStates() {
            when(appointmentRepository.countByMonthAndDoctor(2026, ALL_STATES)).thenReturn(List.of());

            useCase.getMonthlyBreakdown(2026, StatisticDimension.DOCTOR, AppointmentState.AGENDADA);

            verify(appointmentRepository).countByMonthAndDoctor(2026, ALL_STATES);
            assertThat(ALL_STATES).containsExactlyInAnyOrder(AppointmentState.AGENDADA, AppointmentState.ATENDIDA,
                    AppointmentState.CANCELADA, AppointmentState.NO_ASISTIO, AppointmentState.REPROGRAMADA);
        }
    }

    @Nested
    class DimensionDispatchTests {

        @Test
        void doctorDimensionShouldOnlyCallCountByMonthAndDoctor() {
            when(appointmentRepository.countByMonthAndDoctor(2026, ALL_STATES)).thenReturn(List.of());

            useCase.getMonthlyBreakdown(2026, StatisticDimension.DOCTOR, AppointmentState.AGENDADA);

            verify(appointmentRepository).countByMonthAndDoctor(2026, ALL_STATES);
            verify(appointmentRepository, never()).countByMonthAndSpecialty(anyInt(), any());
            verify(appointmentRepository, never()).countByMonthAndState(anyInt());
        }

        @Test
        void specialtyDimensionShouldOnlyCallCountByMonthAndSpecialty() {
            when(appointmentRepository.countByMonthAndSpecialty(2026, List.of(AppointmentState.ATENDIDA)))
                    .thenReturn(List.of());

            useCase.getMonthlyBreakdown(2026, StatisticDimension.SPECIALTY, AppointmentState.ATENDIDA);

            verify(appointmentRepository).countByMonthAndSpecialty(2026, List.of(AppointmentState.ATENDIDA));
            verify(appointmentRepository, never()).countByMonthAndDoctor(anyInt(), any());
            verify(appointmentRepository, never()).countByMonthAndState(anyInt());
        }

        @Test
        void shouldThrowNullPointerWhenDimensionIsNull() {
            assertThatThrownBy(() -> useCase.getMonthlyBreakdown(2026, null, AppointmentState.AGENDADA))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    class MonthlyBreakdownTests {

        @Test
        void shouldReturnTwelveMonthsWithEmptyValuesWhenThereAreNoAppointments() {
            when(appointmentRepository.countByMonthAndDoctor(2026, ALL_STATES)).thenReturn(List.of());

            MonthlyBreakdown result = useCase.getMonthlyBreakdown(
                    2026, StatisticDimension.DOCTOR, AppointmentState.AGENDADA);

            assertThat(result.keys()).isEmpty();
            assertThat(result.rows()).hasSize(12);
            for (int month = 0; month < 12; month++) {
                assertThat(result.rows().get(month).month()).isEqualTo(month);
                assertThat(result.rows().get(month).values()).isEmpty();
            }
        }

        @Test
        void shouldFillMissingMonthsAndKeysWithZero() {
            when(appointmentRepository.countByMonthAndSpecialty(2026, ALL_STATES)).thenReturn(List.of(
                    new MonthlyGroupCount(0, "FISIOTERAPIA", 2),
                    new MonthlyGroupCount(0, "TERAPIA_NEURAL", 1),
                    new MonthlyGroupCount(1, "TERAPIA_NEURAL", 1),
                    new MonthlyGroupCount(11, "FISIOTERAPIA", 1)));

            MonthlyBreakdown result = useCase.getMonthlyBreakdown(
                    2026, StatisticDimension.SPECIALTY, AppointmentState.AGENDADA);

            assertThat(result.keys()).containsExactly("FISIOTERAPIA", "TERAPIA_NEURAL");
            assertThat(result.rows()).extracting(MonthlyBreakdown.MonthlyRow::month)
                    .containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11);
            assertThat(result.rows().get(0).values()).containsEntry("FISIOTERAPIA", 2).containsEntry("TERAPIA_NEURAL", 1);
            assertThat(result.rows().get(1).values()).containsEntry("FISIOTERAPIA", 0).containsEntry("TERAPIA_NEURAL", 1);
            assertThat(result.rows().get(5).values()).containsEntry("FISIOTERAPIA", 0).containsEntry("TERAPIA_NEURAL", 0);
            assertThat(result.rows().get(11).values()).containsEntry("FISIOTERAPIA", 1).containsEntry("TERAPIA_NEURAL", 0);
            // todas las filas llevan todas las claves
            assertThat(result.rows()).allSatisfy(row -> assertThat(row.values()).hasSize(2));
        }

        @Test
        void shouldSumSeveralRowsOfTheSameMonthAndKey() {
            when(appointmentRepository.countByMonthAndDoctor(2026, ALL_STATES)).thenReturn(List.of(
                    new MonthlyGroupCount(3, "doc-a", 2),
                    new MonthlyGroupCount(3, "doc-a", 5)));

            MonthlyBreakdown result = useCase.getMonthlyBreakdown(
                    2026, StatisticDimension.DOCTOR, AppointmentState.AGENDADA);

            assertThat(result.rows().get(3).values()).containsEntry("doc-a", 7);
        }

        @Test
        void shouldSortKeysByTheirStringRepresentation() {
            when(appointmentRepository.countByMonthAndDoctor(2026, ALL_STATES)).thenReturn(List.of(
                    new MonthlyGroupCount(0, "ccc", 1),
                    new MonthlyGroupCount(0, "aaa", 1),
                    new MonthlyGroupCount(1, "bbb", 1)));

            MonthlyBreakdown result = useCase.getMonthlyBreakdown(
                    2026, StatisticDimension.DOCTOR, AppointmentState.AGENDADA);

            assertThat(result.keys()).containsExactly("aaa", "bbb", "ccc");
            assertThat(result.rows().getFirst().values().keySet()).containsExactly("aaa", "bbb", "ccc");
        }

        @Test
        void shouldSortUuidKeysByTheirStringRepresentation() {
            UUID low = UUID.fromString("00000000-0000-0000-0000-000000000001");
            UUID high = UUID.fromString("ffffffff-0000-0000-0000-000000000001");
            when(appointmentRepository.countByMonthAndDoctor(2026, ALL_STATES)).thenReturn(List.of(
                    new MonthlyGroupCount(0, high.toString(), 1),
                    new MonthlyGroupCount(0, low.toString(), 1)));

            MonthlyBreakdown result = useCase.getMonthlyBreakdown(
                    2026, StatisticDimension.DOCTOR, AppointmentState.AGENDADA);

            assertThat(result.keys()).containsExactly(low.toString(), high.toString());
        }
    }

    @Nested
    class DoctorDailyCountsTests {

        private static final LocalDate DATE = LocalDate.of(2040, 3, 15);

        @Test
        void shouldThrowWhenDateIsNull() {
            assertThatThrownBy(() -> useCase.getDoctorDailyCounts(null))
                    .isInstanceOf(InvalidStatisticsDateException.class);

            verifyNoInteractions(appointmentRepository);
        }

        @Test
        void shouldQueryRepositoryWithTheDateAndCanceladaAsExcludedState() {
            when(appointmentRepository.countByDateGroupedByDoctorExcludingState(DATE, AppointmentState.CANCELADA))
                    .thenReturn(List.of());

            useCase.getDoctorDailyCounts(DATE);

            verify(appointmentRepository).countByDateGroupedByDoctorExcludingState(DATE, AppointmentState.CANCELADA);
            verifyNoMoreInteractions(appointmentRepository);
        }

        @Test
        void shouldReturnEmptyListWhenRepositoryReturnsNothing() {
            when(appointmentRepository.countByDateGroupedByDoctorExcludingState(DATE, AppointmentState.CANCELADA))
                    .thenReturn(List.of());

            assertThat(useCase.getDoctorDailyCounts(DATE)).isEmpty();
        }

        @Test
        void shouldReturnCountsSortedByDoctorId() {
            UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
            UUID second = UUID.fromString("11111111-0000-0000-0000-000000000001");
            UUID third = UUID.fromString("77777777-0000-0000-0000-000000000001");
            when(appointmentRepository.countByDateGroupedByDoctorExcludingState(DATE, AppointmentState.CANCELADA))
                    .thenReturn(List.of(
                            new DoctorDailyCount(third, 3),
                            new DoctorDailyCount(first, 1),
                            new DoctorDailyCount(second, 2)));

            List<DoctorDailyCount> result = useCase.getDoctorDailyCounts(DATE);

            assertThat(result).containsExactly(
                    new DoctorDailyCount(first, 1),
                    new DoctorDailyCount(second, 2),
                    new DoctorDailyCount(third, 3));
        }

        @Test
        void shouldSortByStringRepresentationEvenWhenUuidNaturalOrderDiffers() {
            // UUID.compareTo compara con signo: ffff... quedaría ANTES que 0000...; aquí debe quedar después.
            UUID low = UUID.fromString("00000000-0000-0000-0000-000000000001");
            UUID high = UUID.fromString("ffffffff-0000-0000-0000-000000000001");
            when(appointmentRepository.countByDateGroupedByDoctorExcludingState(DATE, AppointmentState.CANCELADA))
                    .thenReturn(List.of(new DoctorDailyCount(high, 1), new DoctorDailyCount(low, 1)));

            assertThat(useCase.getDoctorDailyCounts(DATE)).extracting(DoctorDailyCount::doctorId)
                    .containsExactly(low, high);
        }
    }

    @Nested
    class MonthlyStateCountsTests {

        @Test
        void shouldReturnTwelveMonthsWithEveryStateInZeroWhenThereAreNoAppointments() {
            when(appointmentRepository.countByMonthAndState(2026)).thenReturn(List.of());

            List<MonthlyStateCounts> result = useCase.getMonthlyStateCounts(2026);

            assertThat(result).hasSize(12);
            for (int month = 0; month < 12; month++) {
                MonthlyStateCounts row = result.get(month);
                assertThat(row.month()).isEqualTo(month);
                assertThat(row.counts()).hasSize(AppointmentState.values().length);
                for (AppointmentState state : AppointmentState.values()) {
                    assertThat(row.counts()).containsEntry(state, 0);
                }
            }
        }

        @Test
        void shouldPlaceCountsInTheirMonthAndZeroFillTheRemainingStates() {
            when(appointmentRepository.countByMonthAndState(2026)).thenReturn(List.of(
                    new MonthlyStateCount(0, AppointmentState.ATENDIDA, 2),
                    new MonthlyStateCount(0, AppointmentState.CANCELADA, 1),
                    new MonthlyStateCount(11, AppointmentState.AGENDADA, 1)));

            List<MonthlyStateCounts> result = useCase.getMonthlyStateCounts(2026);

            assertThat(result.get(0).counts())
                    .containsEntry(AppointmentState.ATENDIDA, 2)
                    .containsEntry(AppointmentState.CANCELADA, 1)
                    .containsEntry(AppointmentState.AGENDADA, 0)
                    .containsEntry(AppointmentState.NO_ASISTIO, 0)
                    .containsEntry(AppointmentState.REPROGRAMADA, 0);
            assertThat(result.get(11).counts()).containsEntry(AppointmentState.AGENDADA, 1);
            assertThat(result.get(5).counts().values()).containsOnly(0);
        }

        @Test
        void shouldSumSeveralRowsOfTheSameMonthAndState() {
            when(appointmentRepository.countByMonthAndState(2026)).thenReturn(List.of(
                    new MonthlyStateCount(2, AppointmentState.ATENDIDA, 3),
                    new MonthlyStateCount(2, AppointmentState.ATENDIDA, 4)));

            List<MonthlyStateCounts> result = useCase.getMonthlyStateCounts(2026);

            assertThat(result.get(2).counts()).containsEntry(AppointmentState.ATENDIDA, 7);
            verify(appointmentRepository).countByMonthAndState(2026);
            verifyNoMoreInteractions(appointmentRepository);
        }
    }
}
