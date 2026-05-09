package co.edu.unicauca.piedrazul.backend.report.util;

import co.edu.unicauca.piedrazul.backend.report.dtos.AppointmentReportRow;
import co.edu.unicauca.piedrazul.backend.report.dtos.DailyReportDto;
import co.edu.unicauca.piedrazul.backend.report.dtos.ReportColumn;

import java.util.List;

public final class ReportRowMapper {

    private ReportRowMapper() {
        // Private constructor to prevent instantiation
    }

    //un metodo static pertenece a la clase, no a una instancia de ella
    //por lo que puedo usarlo sin crear un un objeto
    public static List<Object> extraerValores(AppointmentReportRow row,
                                        DailyReportDto report,
                                        List<ReportColumn> columns) {
        return columns.stream().map(col -> switch (col) {
            case FECHA_CITA          -> row.date();
            case HORA_CITA           -> row.startTime();
            case NOMBRE_PACIENTE     -> row.patientFullName();
            case DOCUMENTO_IDENTIDAD -> row.document();
            case TELEFONO_PACIENTE   -> row.phoneNumber();
            case NOMBRE_MEDICO       -> report.doctorFullName();
            case ESPECIALIDAD        -> row.specialty();
            case ESTADO_CITA         -> row.state();
        }).map(Object.class::cast).toList();

    }

}
