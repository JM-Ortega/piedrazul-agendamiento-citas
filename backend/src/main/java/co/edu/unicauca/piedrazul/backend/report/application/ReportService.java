package co.edu.unicauca.piedrazul.backend.report.application;

import co.edu.unicauca.piedrazul.backend.appointment.AppointmentSummary;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.internal.PatientData;
import co.edu.unicauca.piedrazul.backend.appointment.SchedulerAppointmentSummary;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.PatientData;
import co.edu.unicauca.piedrazul.backend.report.domain.CsvExporter;
import co.edu.unicauca.piedrazul.backend.report.domain.ExcelExporter;
import co.edu.unicauca.piedrazul.backend.report.domain.PdfExporter;
import co.edu.unicauca.piedrazul.backend.report.dtos.*;
import co.edu.unicauca.piedrazul.backend.report.dtos.output.AvailabilityResponseDto;
import co.edu.unicauca.piedrazul.backend.report.exception.NoAppointmentsTodayException;
import co.edu.unicauca.piedrazul.backend.report.integration.AppointmentDataClient;
import co.edu.unicauca.piedrazul.backend.report.integration.PatientDataClient;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collector;
import java.util.stream.Collectors;

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

    // Nuevo método de consulta
    public AvailabilityResponseDto checkAvailability(LocalDate date) {
        return new AvailabilityResponseDto(
                appointmentDataClient.hasAvailableSlots(date)
        );
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

    public byte[] exportScheduler(SchedulerRequestDto request) {
        LocalDate date = request.date();

        List<SchedulerAppointmentSummary> appointments = appointmentDataClient.getAllAppointmentsByDate(date);

        if(appointments.isEmpty()) {
            throw new NoAppointmentsTodayException(date);
        }

        // Consultar si hay horarios disponibles para incluir advertencia en el reporte
        boolean hasAvailableSlots = appointmentDataClient.hasAvailableSlots(date);

        List<DoctorDailyScheduleDto> schedules = appointments.stream()
                .collect(Collectors.groupingBy(
                        SchedulerAppointmentSummary::doctorName,
                        LinkedHashMap::new,
                        Collectors.collectingAndThen(
                                Collectors.toList(),
                                list -> list.stream()
                                        .sorted(Comparator.comparing(SchedulerAppointmentSummary::startTime))
                                        .map(SchedulerAppointmentSummary::patientName)
                                        .toList()
                        )
                ))
                .entrySet().stream()
                .map(e -> new DoctorDailyScheduleDto(e.getKey(), e.getValue()))
                .toList();


        return switch (request.format()) {
            case CSV -> csvExporter.exportScheduler(schedules, date, hasAvailableSlots);
            case EXCEL -> excelExporter.exportScheduler(schedules, date, hasAvailableSlots);
            case PDF   -> pdfExporter.exportScheduler(schedules, date, hasAvailableSlots);
        };
    }
}