package co.edu.unicauca.piedrazul.backend.report.dtos;



import java.util.List;
import java.time.LocalDate;
import java.util.UUID;

public record DailyReportDto(
        UUID idDoctor,
        String doctorFullName,
        LocalDate date,
        List<AppointmentReportRow> rows
) {
    public int total(){
        return rows.size();
    }
}
