package co.edu.unicauca.piedrazul.backend.appointment.application;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentState;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.statistics.DoctorDailyCount;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.statistics.MonthlyBreakdown;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.statistics.MonthlyBreakdown.MonthlyRow;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.statistics.MonthlyGroupCount;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.statistics.MonthlyStateCount;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.statistics.MonthlyStateCounts;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.statistics.StatisticDimension;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.GetAppointmentStatisticsUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentRepository;
import co.edu.unicauca.piedrazul.backend.appointment.exception.InvalidStatisticsDateException;
import co.edu.unicauca.piedrazul.backend.appointment.exception.InvalidStatisticsStateException;
import co.edu.unicauca.piedrazul.backend.appointment.exception.InvalidStatisticsYearException;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.IntStream;

public class GetAppointmentStatisticsUseCaseImpl implements GetAppointmentStatisticsUseCase {

    private static final int MIN_YEAR = 2023;
    private static final int MAX_YEAR = 2100;

    private final AppointmentRepository appointmentRepository;

    public GetAppointmentStatisticsUseCaseImpl(AppointmentRepository appointmentRepository) {
        this.appointmentRepository = appointmentRepository;
    }

    @Override
    public MonthlyBreakdown getMonthlyBreakdown(int year, StatisticDimension dimension, AppointmentState state) {
        validateYear(year);
        Objects.requireNonNull(dimension, "dimension is required");

        List<AppointmentState> states = resolveStates(state);

        List<MonthlyGroupCount> counts = switch (dimension) {
            case DOCTOR -> appointmentRepository.countByMonthAndDoctor(year, states);
            case SPECIALTY -> appointmentRepository.countByMonthAndSpecialty(year, states);
        };

        Map<Integer, Map<String, Integer>> byMonth = new HashMap<>();
        Set<String> keys = new TreeSet<>();
        for (MonthlyGroupCount c : counts) {
            keys.add(c.key());
            byMonth.computeIfAbsent(c.month(), m -> new HashMap<>())
                    .merge(c.key(), Math.toIntExact(c.total()), Integer::sum);
        }

        List<MonthlyRow> rows = IntStream.range(0, 12)
                .mapToObj(month -> {
                    Map<String, Integer> source = byMonth.getOrDefault(month, Map.of());
                    Map<String, Integer> values = new LinkedHashMap<>();
                    keys.forEach(k -> values.put(k, source.getOrDefault(k, 0)));
                    return new MonthlyRow(month, values);
                })
                .toList();

        return new MonthlyBreakdown(List.copyOf(keys), rows);
    }

    @Override
    public List<MonthlyStateCounts> getMonthlyStateCounts(int year) {
        validateYear(year);

        Map<Integer, Map<AppointmentState, Integer>> byMonth = new HashMap<>();
        for (MonthlyStateCount c : appointmentRepository.countByMonthAndState(year)) {
            byMonth.computeIfAbsent(c.month(), m -> new EnumMap<>(AppointmentState.class))
                    .merge(c.state(), Math.toIntExact(c.total()), Integer::sum);
        }

        return IntStream.range(0, 12)
                .mapToObj(month -> {
                    Map<AppointmentState, Integer> source = byMonth.getOrDefault(month, Map.of());
                    Map<AppointmentState, Integer> counts = new EnumMap<>(AppointmentState.class);
                    for (AppointmentState s : AppointmentState.values()) {
                        counts.put(s, source.getOrDefault(s, 0));
                    }
                    return new MonthlyStateCounts(month, counts);
                })
                .toList();
    }

    @Override
    public List<DoctorDailyCount> getDoctorDailyCounts(LocalDate date) {
        if (date == null) {
            throw new InvalidStatisticsDateException();
        }
        return appointmentRepository.countByDateGroupedByDoctorExcludingState(date, AppointmentState.CANCELADA).stream()
                // Por String, no por UUID.compareTo (que compara con signo y desordena respecto a la BD/UI)
                .sorted(Comparator.comparing(c -> c.doctorId().toString()))
                .toList();
    }

    /** "Agendadas" = toda cita que alguna vez se agendó, es decir, todas las del sistema sin filtrar estado. */
    private List<AppointmentState> resolveStates(AppointmentState state) {
        if (state == AppointmentState.AGENDADA) {
            return List.of(AppointmentState.values());
        }
        if (state == AppointmentState.ATENDIDA) {
            return List.of(state);
        }
        throw new InvalidStatisticsStateException(String.valueOf(state));
    }

    private void validateYear(int year) {
        if (year < MIN_YEAR || year > MAX_YEAR) {
            throw new InvalidStatisticsYearException(year);
        }
    }
}
