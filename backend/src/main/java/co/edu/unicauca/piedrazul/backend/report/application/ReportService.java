package co.edu.unicauca.piedrazul.backend.report.application;

import co.edu.unicauca.piedrazul.backend.appointment.AppointmentSummary;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.PatientData;
import co.edu.unicauca.piedrazul.backend.report.domain.CsvExporter;
import co.edu.unicauca.piedrazul.backend.report.domain.ExcelExporter;
import co.edu.unicauca.piedrazul.backend.report.domain.PdfExporter;
import co.edu.unicauca.piedrazul.backend.report.dtos.AppointmentReportRow;
import co.edu.unicauca.piedrazul.backend.report.dtos.DailyReportDto;
import co.edu.unicauca.piedrazul.backend.report.dtos.ExportRequestDto;
import co.edu.unicauca.piedrazul.backend.report.exception.NoAppointmentsTodayException;
import co.edu.unicauca.piedrazul.backend.report.integration.AppointmentDataClient;
import co.edu.unicauca.piedrazul.backend.report.integration.PatientDataClient;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Service
public class ReportService {

    private final AppointmentDataClient appointmentDataClient;
    private final PatientDataClient patientDataClient;
    public final CsvExporter csvExporter;
    public final ExcelExporter excelExporter;
    public final PdfExporter pdfExporter;

    public ReportService(AppointmentDataClient appointmentDataClient,
                         PatientDataClient patientDataClient,
                         CsvExporter csvExporter,
                         ExcelExporter excelExporter,
                         PdfExporter pdfExporter) {
        this.appointmentDataClient = appointmentDataClient;
        this.patientDataClient = patientDataClient;
        this.csvExporter = csvExporter;
        this.excelExporter = excelExporter;
        this.pdfExporter = pdfExporter;
    }

    public byte[] export(ExportRequestDto request) {
        LocalDate today = LocalDate.now();

        List<AppointmentSummary> appointments =
                appointmentDataClient.getAppointmentForDoctorsToday(request.idDoctor(), request.state());

        if (appointments.isEmpty()) {
            throw new NoAppointmentsTodayException(today);
        }

        String doctorName = appointments.get(0).doctorName();

        List<AppointmentReportRow> rows = appointments.stream()
                .map(a -> {
                    // Una sola llamada a patients por cita, se reutilizan ambos campos
                    PatientData patient = a.idPatient() != null
                            ? patientDataClient.getPatientData(a.idPatient())
                            : null;

                    return new AppointmentReportRow(
                            a.idAppointment(),
                            a.idPatient(),
                            a.patientFullName(),
                            patient != null ? patient.documentNumber() : "",
                            patient != null ? patient.phone() : "",
                            a.date(),
                            a.startTime(),
                            a.specialty(),
                            a.state()
                    );
                }).toList();

        DailyReportDto report = new DailyReportDto(request.idDoctor(), doctorName, today, rows);

        return switch (request.format()) {
            case CSV   -> csvExporter.export(report, request.columns());
            case EXCEL -> excelExporter.export(report, request.columns());
            case PDF   -> pdfExporter.export(report, request.columns());
        };
    }
}