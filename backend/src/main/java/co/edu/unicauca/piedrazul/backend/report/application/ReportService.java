package co.edu.unicauca.piedrazul.backend.report.application;

import co.edu.unicauca.piedrazul.backend.appointment.AppointmentSummary;
import co.edu.unicauca.piedrazul.backend.report.csv.CsvExporter;
import co.edu.unicauca.piedrazul.backend.report.dtos.AppointmentReportRow;
import co.edu.unicauca.piedrazul.backend.report.dtos.DailyReportDto;
import co.edu.unicauca.piedrazul.backend.report.exception.NoAppointmentsTodayException;
import co.edu.unicauca.piedrazul.backend.report.integration.AppointmentDataClient;
import co.edu.unicauca.piedrazul.backend.report.integration.DoctorDataClient;
import co.edu.unicauca.piedrazul.backend.report.integration.PatientDataClient;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
public class ReportService {

    private final AppointmentDataClient appointmentDataClient;
    private final DoctorDataClient doctorDataClient;
    private final PatientDataClient patientDataClient;
    public final CsvExporter csvExporter;

    public ReportService(AppointmentDataClient appointmentDataClient,
                         DoctorDataClient doctorDataClient,
                         PatientDataClient patientDataClient,
                         CsvExporter csvExporter) {
        this.appointmentDataClient = appointmentDataClient;
        this.doctorDataClient = doctorDataClient;
        this.patientDataClient = patientDataClient;
        this.csvExporter = csvExporter;
    }

    public byte[] exportDailyAppointmentsCSV(UUID idDoctor){
        LocalDate today = LocalDate.now();

        List<AppointmentSummary> appointments = appointmentDataClient.getAppointmentForDoctorsToday(idDoctor);

        //verifica que hayan citas para el dia de hoy, si no hay lanza una excepcion
        if(appointments.isEmpty()){
            throw new NoAppointmentsTodayException(today);
        }

        String doctorName = doctorDataClient.getDoctorFullName(idDoctor);

        List<AppointmentReportRow> rows = appointments.stream()
                .map(a -> new AppointmentReportRow(
                        a.idAppointment(),
                        a.idPatient(),
                        patientDataClient.getPatientFullName(a.idPatient()),
                        a.date(),
                        a.startTime()
                )).toList();

        DailyReportDto repor = new DailyReportDto(idDoctor, doctorName, today, rows);

        return csvExporter.export(repor);
    }
}
