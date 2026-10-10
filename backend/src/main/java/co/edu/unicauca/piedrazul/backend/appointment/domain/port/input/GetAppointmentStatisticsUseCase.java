package co.edu.unicauca.piedrazul.backend.appointment.domain.port.input;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentState;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.statistics.MonthlyBreakdown;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.statistics.MonthlyStateCounts;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.statistics.StatisticDimension;

import java.util.List;

public interface GetAppointmentStatisticsUseCase {

    /** @param state si es null se cuentan AGENDADA y ATENDIDA */
    MonthlyBreakdown getMonthlyBreakdown(int year, StatisticDimension dimension, AppointmentState state);

    List<MonthlyStateCounts> getMonthlyStateCounts(int year);
}
