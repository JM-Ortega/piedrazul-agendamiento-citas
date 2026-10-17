package co.edu.unicauca.piedrazul.backend.statistics.domain;

import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output.DoctorAppointmentsCountDto;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output.DoctorDailyCountDto;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output.MonthlyCancellationDataDto;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output.MonthlyDoctorRowDto;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output.MonthlySpecialtyRowDto;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output.MonthlyTotalAppointmentDto;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output.SpecialtyAppointmentsCountDto;
import co.edu.unicauca.piedrazul.backend.shared.enums.SpecialtyCode;
import co.edu.unicauca.piedrazul.backend.statistics.dtos.output.CancellationRateDto;
import co.edu.unicauca.piedrazul.backend.statistics.dtos.output.DailyWorkloadDto;
import co.edu.unicauca.piedrazul.backend.statistics.dtos.output.DoctorWorkloadDto;
import co.edu.unicauca.piedrazul.backend.statistics.dtos.output.MonthlyBreakdownDto;
import co.edu.unicauca.piedrazul.backend.statistics.dtos.output.MonthlyRateDto;
import co.edu.unicauca.piedrazul.backend.statistics.dtos.output.MonthlyTotalDto;
import co.edu.unicauca.piedrazul.backend.statistics.dtos.WorkloadLevel;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.assertj.core.api.Assertions.tuple;

class StatisticsAssemblerTest {

    private static final UUID ANDRES = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID CARLOS = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID DIANA = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID JULIAN = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID LAURA = UUID.fromString("00000000-0000-0000-0000-000000000005");

    private static final Map<UUID, String> NAMES = Map.of(
            ANDRES, "Andrés Pérez",
            CARLOS, "Carlos Ramírez",
            DIANA, "Diana López",
            JULIAN, "Julián Torres",
            LAURA, "Laura Gómez");

    private final StatisticsAssembler assembler = new StatisticsAssembler();

    @Nested
    class AvailableYears {

        @Test
        void goesFromNextYearDownTo2000() {
            List<Integer> years = assembler.availableYears(2026);

            assertThat(years).hasSize(28);
            assertThat(years.getFirst()).isEqualTo(2027);
            assertThat(years.getLast()).isEqualTo(2000);
            assertThat(years).isSortedAccordingTo(Comparator.reverseOrder());
        }
    }

    @Nested
    class MonthlyTotals {

        @Test
        void keepsMonthAndTotal() {
            List<MonthlyTotalDto> result = assembler.monthlyTotals(List.of(
                    new MonthlyTotalAppointmentDto(0, 120),
                    new MonthlyTotalAppointmentDto(1, 98)));

            assertThat(result).containsExactly(new MonthlyTotalDto(0, 120), new MonthlyTotalDto(1, 98));
        }
    }

    @Nested
    class ByDoctor {

        @Test
        void replacesIdsWithNamesAndOrdersSeriesByName() {
            DoctorAppointmentsCountDto data = new DoctorAppointmentsCountDto(
                    List.of(LAURA, CARLOS),
                    List.of(
                            new MonthlyDoctorRowDto(0, Map.of(LAURA, 9, CARLOS, 12)),
                            new MonthlyDoctorRowDto(1, Map.of(LAURA, 0, CARLOS, 15))));

            MonthlyBreakdownDto result = assembler.byDoctor(data, NAMES);

            assertThat(result.series()).containsExactly("Carlos Ramírez", "Laura Gómez");
            assertThat(result.rows()).hasSize(2);
            assertThat(result.rows().get(0).month()).isZero();
            assertThat(result.rows().get(0).values())
                    .containsExactly(entry("Carlos Ramírez", 12), entry("Laura Gómez", 9));
            assertThat(result.rows().get(1).values())
                    .containsExactly(entry("Carlos Ramírez", 15), entry("Laura Gómez", 0));
        }

        @Test
        void yearWithoutAppointmentsKeepsMonthsWithEmptyValues() {
            DoctorAppointmentsCountDto data = new DoctorAppointmentsCountDto(
                    List.of(), List.of(new MonthlyDoctorRowDto(0, Map.of())));

            MonthlyBreakdownDto result = assembler.byDoctor(data, NAMES);

            assertThat(result.series()).isEmpty();
            assertThat(result.rows()).singleElement()
                    .satisfies(row -> assertThat(row.values()).isEmpty());
        }
    }

    @Nested
    class BySpecialty {

        @Test
        void sendsSpecialtyCodeAsSeries() {
            SpecialtyAppointmentsCountDto data = new SpecialtyAppointmentsCountDto(
                    List.of(SpecialtyCode.FISIOTERAPIA, SpecialtyCode.TERAPIA_NEURAL),
                    List.of(new MonthlySpecialtyRowDto(0,
                            Map.of(SpecialtyCode.TERAPIA_NEURAL, 21, SpecialtyCode.FISIOTERAPIA, 9))));

            MonthlyBreakdownDto result = assembler.bySpecialty(data);

            assertThat(result.series()).containsExactly("FISIOTERAPIA", "TERAPIA_NEURAL");
            assertThat(result.rows().getFirst().values())
                    .containsExactly(entry("FISIOTERAPIA", 9), entry("TERAPIA_NEURAL", 21));
        }
    }

    @Nested
    class CancellationRate {

        @Test
        void calculatesMonthlyRateAndYearRateFromTotals() {
            CancellationRateDto result = assembler.cancellationRate(List.of(
                    new MonthlyCancellationDataDto(0, 100, 12),
                    new MonthlyCancellationDataDto(1, 0, 0),
                    new MonthlyCancellationDataDto(2, 3, 1)));

            assertThat(result.months()).containsExactly(
                    new MonthlyRateDto(0, 12, 12),
                    new MonthlyRateDto(1, 0, 0),
                    new MonthlyRateDto(2, 33, 1));
            // 13 de 103 citas = 12,6 % -> 13 (no el promedio de los meses)
            assertThat(result.yearRate()).isEqualTo(13);
            assertThat(result.yearCount()).isEqualTo(13);
        }

        @Test
        void yearWithoutAppointmentsHasZeroRate() {
            CancellationRateDto result = assembler.cancellationRate(List.of(
                    new MonthlyCancellationDataDto(0, 0, 0)));

            assertThat(result.yearRate()).isZero();
            assertThat(result.yearCount()).isZero();
        }
    }

    @Nested
    class DailyWorkload {

        @Test
        void assignsLevelsAgainstAverageOfDoctorsWithAppointments() {
            // 24 citas entre 4 doctores con citas -> promedio 6 (Diana, sin citas, no cuenta)
            List<DoctorDailyCountDto> counts = List.of(
                    new DoctorDailyCountDto(ANDRES, 1),
                    new DoctorDailyCountDto(CARLOS, 2),
                    new DoctorDailyCountDto(JULIAN, 8),
                    new DoctorDailyCountDto(LAURA, 13));

            DailyWorkloadDto result = assembler.dailyWorkload(
                    counts, List.of(ANDRES, CARLOS, DIANA, JULIAN, LAURA), NAMES);

            assertThat(result.dayTotal()).isEqualTo(24);
            assertThat(result.averagePerDoctor()).isEqualTo(6);
            assertThat(result.doctors())
                    .extracting(DoctorWorkloadDto::doctor, DoctorWorkloadDto::total, DoctorWorkloadDto::level)
                    .containsExactly(
                            tuple("Andrés Pérez", 1, WorkloadLevel.NORMAL),
                            tuple("Carlos Ramírez", 2, WorkloadLevel.NORMAL),
                            tuple("Diana López", 0, WorkloadLevel.SIN_CITAS),
                            tuple("Julián Torres", 8, WorkloadLevel.ALTA),
                            tuple("Laura Gómez", 13, WorkloadLevel.SOBRECARGA));
        }

        @Test
        void dayWithoutAppointmentsMarksEveryActiveDoctorWithoutAppointments() {
            DailyWorkloadDto result = assembler.dailyWorkload(List.of(), List.of(CARLOS, LAURA), NAMES);

            assertThat(result.dayTotal()).isZero();
            assertThat(result.averagePerDoctor()).isZero();
            assertThat(result.doctors())
                    .extracting(DoctorWorkloadDto::level)
                    .containsOnly(WorkloadLevel.SIN_CITAS);
        }

        @Test
        void singleDoctorWithAppointmentsIsNormal() {
            DailyWorkloadDto result = assembler.dailyWorkload(
                    List.of(new DoctorDailyCountDto(CARLOS, 9)), List.of(CARLOS, LAURA), NAMES);

            assertThat(result.averagePerDoctor()).isEqualTo(9);
            assertThat(result.doctors())
                    .extracting(DoctorWorkloadDto::doctor, DoctorWorkloadDto::level)
                    .containsExactly(
                            tuple("Carlos Ramírez", WorkloadLevel.NORMAL),
                            tuple("Laura Gómez", WorkloadLevel.SIN_CITAS));
        }

        @Test
        void includesInactiveDoctorWithAppointmentsThatDay() {
            DailyWorkloadDto result = assembler.dailyWorkload(
                    List.of(new DoctorDailyCountDto(JULIAN, 4)), List.of(CARLOS), NAMES);

            assertThat(result.doctors())
                    .extracting(DoctorWorkloadDto::doctor)
                    .containsExactly("Carlos Ramírez", "Julián Torres");
        }
    }
}
