package co.edu.unicauca.piedrazul.backend.statistics.domain;

import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output.DoctorAppointmentsCountDto;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output.DoctorDailyCountDto;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output.MonthlyCancellationDataDto;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output.MonthlyTotalAppointmentDto;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output.SpecialtyAppointmentsCountDto;
import co.edu.unicauca.piedrazul.backend.statistics.dtos.output.CancellationRateDto;
import co.edu.unicauca.piedrazul.backend.statistics.dtos.output.DailyWorkloadDto;
import co.edu.unicauca.piedrazul.backend.statistics.dtos.output.DoctorWorkloadDto;
import co.edu.unicauca.piedrazul.backend.statistics.dtos.output.MonthlyBreakdownDto;
import co.edu.unicauca.piedrazul.backend.statistics.dtos.output.MonthlyRateDto;
import co.edu.unicauca.piedrazul.backend.statistics.dtos.output.MonthlyTotalDto;
import co.edu.unicauca.piedrazul.backend.statistics.dtos.output.MonthlyValuesDto;
import co.edu.unicauca.piedrazul.backend.statistics.dtos.WorkloadLevel;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.IntStream;

/**
 * Adapta los conteos del módulo appointment al contrato del frontend.
 * Lógica pura: no consulta nada, solo transforma.
 */
@Component
public class StatisticsAssembler {

    public static final int MIN_YEAR = 2023;

    /** ALTA: más de un 30% sobre el promedio. */
    private static final double HIGH_LOAD_FACTOR = 1.3;
    /** SOBRECARGA: dos veces el promedio o más. */
    private static final double OVERLOAD_FACTOR = 2.0;

    /** Del año siguiente al actual hasta MIN_YEAR, en orden descendente. */
    public List<Integer> availableYears(int currentYear) {
        return IntStream.iterate(currentYear + 1, year -> year >= MIN_YEAR, year -> year - 1)
                .boxed()
                .toList();
    }

    public List<MonthlyTotalDto> monthlyTotals(List<MonthlyTotalAppointmentDto> totals) {
        return totals.stream()
                .map(t -> new MonthlyTotalDto(t.month(), t.total()))
                .toList();
    }

    /** Reemplaza los ids de los doctores por sus nombres; las series quedan ordenadas por nombre. */
    public MonthlyBreakdownDto byDoctor(DoctorAppointmentsCountDto data, Map<UUID, String> doctorNames) {
        List<String> series = data.doctorIds().stream()
                .map(doctorNames::get)
                .sorted()
                .toList();

        List<MonthlyValuesDto> rows = data.rows().stream()
                .map(row -> toRow(row.month(), row.values(), doctorNames::get, series))
                .toList();

        return new MonthlyBreakdownDto(series, rows);
    }

    /** Las especialidades se envían con su código (p. ej. TERAPIA_NEURAL). */
    public MonthlyBreakdownDto bySpecialty(SpecialtyAppointmentsCountDto data) {
        List<String> series = data.specialtys().stream()
                .map(Enum::name)
                .toList();

        List<MonthlyValuesDto> rows = data.rows().stream()
                .map(row -> toRow(row.month(), row.values(), Enum::name, series))
                .toList();

        return new MonthlyBreakdownDto(series, rows);
    }

    /**
     * rate de cada mes = citas del motivo / citas del mes (todos los estados).
     * yearRate se calcula con los totales del año, no promediando los meses.
     */
    public CancellationRateDto cancellationRate(List<MonthlyCancellationDataDto> data) {
        int yearTotal = data.stream().mapToInt(MonthlyCancellationDataDto::totalAppointments).sum();
        int yearCount = data.stream().mapToInt(MonthlyCancellationDataDto::count).sum();

        List<MonthlyRateDto> months = data.stream()
                .map(m -> new MonthlyRateDto(m.month(), percentage(m.count(), m.totalAppointments()), m.count()))
                .toList();

        return new CancellationRateDto(months, percentage(yearCount, yearTotal), yearCount);
    }

    /**
     * Incluye a todos los doctores activos (los que no tienen citas quedan en SIN_CITAS) y a cualquier
     * doctor con citas ese día. El promedio solo considera a los doctores con al menos una cita.
     */
    public DailyWorkloadDto dailyWorkload(List<DoctorDailyCountDto> counts,
                                          Collection<UUID> activeDoctorIds,
                                          Map<UUID, String> doctorNames) {
        Map<UUID, Integer> totalsByDoctor = new HashMap<>();
        activeDoctorIds.forEach(id -> totalsByDoctor.put(id, 0));
        counts.forEach(c -> totalsByDoctor.put(c.doctorId(), c.total()));

        int dayTotal = counts.stream().mapToInt(DoctorDailyCountDto::total).sum();
        long doctorsWithAppointments = totalsByDoctor.values().stream().filter(total -> total > 0).count();
        int average = doctorsWithAppointments == 0
                ? 0
                : (int) Math.round((double) dayTotal / doctorsWithAppointments);

        List<DoctorWorkloadDto> doctors = totalsByDoctor.entrySet().stream()
                .map(e -> new DoctorWorkloadDto(
                        doctorNames.get(e.getKey()),
                        e.getValue(),
                        levelOf(e.getValue(), average)))
                .sorted(Comparator.comparing(DoctorWorkloadDto::doctor))
                .toList();

        return new DailyWorkloadDto(dayTotal, average, doctors);
    }

    /** Se compara contra el mismo promedio (redondeado) que recibe el frontend. */
    private WorkloadLevel levelOf(int total, int average) {
        if (total == 0) {
            return WorkloadLevel.SIN_CITAS;
        }
        if (total >= average * OVERLOAD_FACTOR) {
            return WorkloadLevel.SOBRECARGA;
        }
        if (total > average * HIGH_LOAD_FACTOR) {
            return WorkloadLevel.ALTA;
        }
        return WorkloadLevel.NORMAL;
    }

    private int percentage(int part, int total) {
        return total == 0 ? 0 : (int) Math.round(part * 100.0 / total);
    }

    /** Arma los valores del mes siguiendo el orden de series; las que no vengan quedan en 0. */
    private <K> MonthlyValuesDto toRow(int month,
                                       Map<K, Integer> values,
                                       Function<K, String> label,
                                       List<String> series) {
        Map<String, Integer> byLabel = new HashMap<>();
        values.forEach((key, total) -> byLabel.put(label.apply(key), total));

        Map<String, Integer> ordered = new LinkedHashMap<>();
        series.forEach(s -> ordered.put(s, byLabel.getOrDefault(s, 0)));

        return new MonthlyValuesDto(month, ordered);
    }
}
