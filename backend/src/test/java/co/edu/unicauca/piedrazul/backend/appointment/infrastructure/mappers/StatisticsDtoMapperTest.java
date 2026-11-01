package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.mappers;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentState;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.statistics.DoctorDailyCount;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.statistics.MonthlyBreakdown;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.statistics.MonthlyStateCounts;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output.DoctorAppointmentsCountDto;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output.DoctorDailyCountDto;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output.MonthlyCancellationDataDto;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output.MonthlyTotalAppointmentDto;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output.SpecialtyAppointmentsCountDto;
import co.edu.unicauca.piedrazul.backend.shared.enums.SpecialtyCode;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StatisticsDtoMapperTest {

    /**
     * Enero: 1 AGENDADA, 2 ATENDIDA, 1 CANCELADA, 3 NO_ASISTIO, 0 REPROGRAMADA (total 7).
     * Febrero: 4 REPROGRAMADA. Resto de meses en cero.
     */
    private List<MonthlyStateCounts> stateCounts() {
        return IntStream.range(0, 12).mapToObj(month -> {
            Map<AppointmentState, Integer> counts = new EnumMap<>(AppointmentState.class);
            for (AppointmentState s : AppointmentState.values()) {
                counts.put(s, 0);
            }
            if (month == 0) {
                counts.put(AppointmentState.AGENDADA, 1);
                counts.put(AppointmentState.ATENDIDA, 2);
                counts.put(AppointmentState.CANCELADA, 1);
                counts.put(AppointmentState.NO_ASISTIO, 3);
            }
            if (month == 1) {
                counts.put(AppointmentState.REPROGRAMADA, 4);
            }
            return new MonthlyStateCounts(month, counts);
        }).toList();
    }

    @Test
    void toDoctorDtoShouldConvertKeysToUuidKeepingOrderAndValues() {
        UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID second = UUID.fromString("00000000-0000-0000-0000-000000000002");
        Map<String, Integer> january = new LinkedHashMap<>();
        january.put(first.toString(), 2);
        january.put(second.toString(), 1);
        Map<String, Integer> february = new LinkedHashMap<>();
        february.put(first.toString(), 0);
        february.put(second.toString(), 5);
        MonthlyBreakdown breakdown = new MonthlyBreakdown(
                List.of(first.toString(), second.toString()),
                List.of(new MonthlyBreakdown.MonthlyRow(0, january), new MonthlyBreakdown.MonthlyRow(1, february)));

        DoctorAppointmentsCountDto result = StatisticsDtoMapper.toDoctorDto(breakdown);

        assertThat(result.doctorIds()).containsExactly(first, second);
        assertThat(result.rows()).hasSize(2);
        assertThat(result.rows().get(0).month()).isZero();
        assertThat(result.rows().get(0).values()).containsEntry(first, 2).containsEntry(second, 1);
        assertThat(result.rows().get(1).month()).isEqualTo(1);
        assertThat(result.rows().get(1).values()).containsEntry(first, 0).containsEntry(second, 5);
        assertThat(result.rows().get(0).values().keySet()).containsExactly(first, second);
    }

    @Test
    void toSpecialtyDtoShouldConvertKeysToSpecialtyCode() {
        Map<String, Integer> january = new LinkedHashMap<>();
        january.put("FISIOTERAPIA", 2);
        january.put("TERAPIA_NEURAL", 1);
        MonthlyBreakdown breakdown = new MonthlyBreakdown(
                List.of("FISIOTERAPIA", "TERAPIA_NEURAL"), List.of(new MonthlyBreakdown.MonthlyRow(0, january)));

        SpecialtyAppointmentsCountDto result = StatisticsDtoMapper.toSpecialtyDto(breakdown);

        assertThat(result.specialtys()).containsExactly(SpecialtyCode.FISIOTERAPIA, SpecialtyCode.TERAPIA_NEURAL);
        assertThat(result.rows().getFirst().values())
                .containsEntry(SpecialtyCode.FISIOTERAPIA, 2)
                .containsEntry(SpecialtyCode.TERAPIA_NEURAL, 1);
    }

    @Test
    void toDoctorDtoAndToSpecialtyDtoShouldHandleEmptyBreakdown() {
        MonthlyBreakdown empty = new MonthlyBreakdown(List.of(), List.of(
                new MonthlyBreakdown.MonthlyRow(0, Map.of())));

        assertThat(StatisticsDtoMapper.toDoctorDto(empty).doctorIds()).isEmpty();
        assertThat(StatisticsDtoMapper.toSpecialtyDto(empty).specialtys()).isEmpty();
        assertThat(StatisticsDtoMapper.toDoctorDto(empty).rows().getFirst().values()).isEmpty();
    }

    @Test
    void toMonthlyTotalsShouldTakeOnlyAtendidaCountPerMonth() {
        List<MonthlyTotalAppointmentDto> result = StatisticsDtoMapper.toMonthlyTotals(stateCounts());

        assertThat(result).hasSize(12);
        assertThat(result.get(0)).isEqualTo(new MonthlyTotalAppointmentDto(0, 2));
        assertThat(result.get(1)).isEqualTo(new MonthlyTotalAppointmentDto(1, 0));
        assertThat(result).extracting(MonthlyTotalAppointmentDto::month)
                .containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11);
    }

    @Test
    void toCancellationDataShouldCountCanceladaPlusNoAsistioWhenStateIsNull() {
        List<MonthlyCancellationDataDto> result = StatisticsDtoMapper.toCancellationData(stateCounts(), null);

        assertThat(result).hasSize(12);
        assertThat(result.get(0)).isEqualTo(new MonthlyCancellationDataDto(0, 7, 4));
        // REPROGRAMADA suma al total pero no al conteo por defecto
        assertThat(result.get(1)).isEqualTo(new MonthlyCancellationDataDto(1, 4, 0));
        assertThat(result.get(2)).isEqualTo(new MonthlyCancellationDataDto(2, 0, 0));
    }

    @Test
    void toCancellationDataShouldCountOnlyCanceladaWhenStateIsCancelada() {
        List<MonthlyCancellationDataDto> result =
                StatisticsDtoMapper.toCancellationData(stateCounts(), AppointmentState.CANCELADA);

        assertThat(result.get(0)).isEqualTo(new MonthlyCancellationDataDto(0, 7, 1));
        assertThat(result.get(1)).isEqualTo(new MonthlyCancellationDataDto(1, 4, 0));
    }

    @Test
    void toCancellationDataShouldCountOnlyNoAsistioWhenStateIsNoAsistio() {
        List<MonthlyCancellationDataDto> result =
                StatisticsDtoMapper.toCancellationData(stateCounts(), AppointmentState.NO_ASISTIO);

        assertThat(result.get(0)).isEqualTo(new MonthlyCancellationDataDto(0, 7, 3));
        assertThat(result.get(1)).isEqualTo(new MonthlyCancellationDataDto(1, 4, 0));
    }

    @Test
    void toCancellationDataShouldCountOnlyAtendidaWhenStateIsAtendida() {
        List<MonthlyCancellationDataDto> result =
                StatisticsDtoMapper.toCancellationData(stateCounts(), AppointmentState.ATENDIDA);

        assertThat(result.get(0)).isEqualTo(new MonthlyCancellationDataDto(0, 7, 2));
        assertThat(result.get(1)).isEqualTo(new MonthlyCancellationDataDto(1, 4, 0));
    }

    @Test
    void toCancellationDataShouldTreatMissingStatesAsZero() {
        // Mapa disperso (no todos los estados presentes): no debe fallar ni contar de más.
        Map<AppointmentState, Integer> sparse = new EnumMap<>(AppointmentState.class);
        sparse.put(AppointmentState.CANCELADA, 2);

        List<MonthlyCancellationDataDto> result = StatisticsDtoMapper.toCancellationData(
                List.of(new MonthlyStateCounts(0, sparse)), null);

        assertThat(result).containsExactly(new MonthlyCancellationDataDto(0, 2, 2));
    }

    @Test
    void toDoctorDailyCountDtosShouldConvertDoctorIdAndNarrowTotalToInt() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        List<DoctorDailyCountDto> result = StatisticsDtoMapper.toDoctorDailyCountDtos(
                List.of(new DoctorDailyCount(first, 2L), new DoctorDailyCount(second, 3L)));

        assertThat(result).containsExactly(new DoctorDailyCountDto(first, 2), new DoctorDailyCountDto(second, 3));
    }

    @Test
    void toDoctorDailyCountDtosShouldReturnEmptyListWhenThereAreNoCounts() {
        assertThat(StatisticsDtoMapper.toDoctorDailyCountDtos(List.of())).isEmpty();
    }

    @Test
    void toDoctorDailyCountDtosShouldFailWhenTotalDoesNotFitInInt() {
        assertThatThrownBy(() -> StatisticsDtoMapper.toDoctorDailyCountDtos(
                List.of(new DoctorDailyCount(UUID.randomUUID(), Integer.MAX_VALUE + 1L))))
                .isInstanceOf(ArithmeticException.class);
    }
}
