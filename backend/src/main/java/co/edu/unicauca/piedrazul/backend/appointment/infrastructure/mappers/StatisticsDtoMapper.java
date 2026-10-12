package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.mappers;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentState;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.statistics.DoctorDailyCount;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.statistics.MonthlyBreakdown;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.statistics.MonthlyStateCounts;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output.*;
import co.edu.unicauca.piedrazul.backend.shared.enums.SpecialtyCode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

public final class StatisticsDtoMapper {

    private StatisticsDtoMapper() {
    }

    public static DoctorAppointmentsCountDto toDoctorDto(MonthlyBreakdown breakdown) {
        return new DoctorAppointmentsCountDto(
                breakdown.keys().stream().map(UUID::fromString).toList(),
                breakdown.rows().stream()
                        .map(r -> new MonthlyDoctorRowDto(r.month(), remapKeys(r.values(), UUID::fromString)))
                        .toList());
    }

    public static SpecialtyAppointmentsCountDto toSpecialtyDto(MonthlyBreakdown breakdown) {
        return new SpecialtyAppointmentsCountDto(
                breakdown.keys().stream().map(SpecialtyCode::valueOf).toList(),
                breakdown.rows().stream()
                        .map(r -> new MonthlySpecialtyRowDto(r.month(), remapKeys(r.values(), SpecialtyCode::valueOf)))
                        .toList());
    }

    public static List<MonthlyTotalAppointmentDto> toMonthlyTotals(List<MonthlyStateCounts> counts) {
        return counts.stream()
                .map(c -> new MonthlyTotalAppointmentDto(c.month(), count(c, AppointmentState.ATENDIDA)))
                .toList();
    }

    /** @param state si es null, count = CANCELADA + NO_ASISTIO */
    public static List<MonthlyCancellationDataDto> toCancellationData(List<MonthlyStateCounts> counts, AppointmentState state) {
        return counts.stream()
                .map(c -> new MonthlyCancellationDataDto(
                        c.month(),
                        c.counts().values().stream().mapToInt(Integer::intValue).sum(),
                        state != null
                                ? count(c, state)
                                : count(c, AppointmentState.CANCELADA) + count(c, AppointmentState.NO_ASISTIO)))
                .toList();
    }

    public static List<DoctorDailyCountDto> toDoctorDailyCountDtos(List<DoctorDailyCount> counts) {
        return counts.stream()
                .map(c -> new DoctorDailyCountDto(c.doctorId(), Math.toIntExact(c.total())))
                .toList();
    }

    private static int count(MonthlyStateCounts counts, AppointmentState state) {
        return counts.counts().getOrDefault(state, 0);
    }

    private static <K> Map<K, Integer> remapKeys(Map<String, Integer> values, Function<String, K> keyMapper) {
        Map<K, Integer> result = new LinkedHashMap<>();
        values.forEach((k, v) -> result.put(keyMapper.apply(k), v));
        return result;
    }
}
