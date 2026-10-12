package co.edu.unicauca.piedrazul.backend.appointment.domain.port.input;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentState;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.statistics.DoctorDailyCount;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.statistics.MonthlyBreakdown;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.statistics.MonthlyStateCounts;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.statistics.StatisticDimension;

import java.time.LocalDate;
import java.util.List;

public interface GetAppointmentStatisticsUseCase {

    /**
     * @param state obligatorio. AGENDADA = todas las citas del sistema (cualquier estado);
     *              ATENDIDA = solo las atendidas. Otro valor o null lanza InvalidStatisticsStateException.
     */
    MonthlyBreakdown getMonthlyBreakdown(int year, StatisticDimension dimension, AppointmentState state);

    List<MonthlyStateCounts> getMonthlyStateCounts(int year);

    /**
     * Total de citas por doctor en la fecha, sin contar las CANCELADA, ordenado por doctorId.
     * Solo incluye doctores con al menos una cita. {@code date} nulo lanza InvalidStatisticsDateException.
     */
    List<DoctorDailyCount> getDoctorDailyCounts(LocalDate date);
}
