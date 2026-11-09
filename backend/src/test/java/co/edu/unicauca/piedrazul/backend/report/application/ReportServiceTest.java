package co.edu.unicauca.piedrazul.backend.report.application;

import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.internal.SchedulerAppointmentSummary;
import co.edu.unicauca.piedrazul.backend.report.domain.CsvExporter;
import co.edu.unicauca.piedrazul.backend.report.domain.ExcelExporter;
import co.edu.unicauca.piedrazul.backend.report.domain.PdfExporter;
import co.edu.unicauca.piedrazul.backend.report.dtos.ExportFormat;
import co.edu.unicauca.piedrazul.backend.report.dtos.SchedulerRequestDto;
import co.edu.unicauca.piedrazul.backend.report.exception.NoAppointmentsTodayException;
import co.edu.unicauca.piedrazul.backend.report.integration.AppointmentDataClient;
import co.edu.unicauca.piedrazul.backend.report.integration.PatientDataClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportServiceTest {

    @Mock
    private AppointmentDataClient appointmentDataClient;
    @Mock
    private PatientDataClient patientDataClient;

    private ReportService service;

    @BeforeEach
    void setUp() {
        service = new ReportService(appointmentDataClient, patientDataClient,
                new CsvExporter(), new ExcelExporter(), new PdfExporter());
    }

    @Nested
    class ExportSchedulerTests {

        @Test
        void shouldExportPastDateWhenAppointmentsAreNotAgendada() {
            // El cliente ya entrega las citas ATENDIDA / NO_ASISTIO de una fecha pasada
            LocalDate pastDate = LocalDate.now().minusDays(10);
            when(appointmentDataClient.getAllAppointmentsByDate(pastDate)).thenReturn(List.of(
                    new SchedulerAppointmentSummary("Dra. Prueba", "Carlos Gomez", LocalTime.of(9, 0)),
                    new SchedulerAppointmentSummary("Dra. Prueba", "Ana Perez", LocalTime.of(8, 0))));
            when(appointmentDataClient.hasAvailableSlots(pastDate)).thenReturn(false);

            byte[] file = service.exportScheduler(new SchedulerRequestDto(pastDate, ExportFormat.CSV));

            String content = new String(file, StandardCharsets.UTF_8);
            assertThat(content).contains("Dra. Prueba");
            // Los pacientes salen ordenados por hora de inicio
            assertThat(content.indexOf("Ana Perez")).isPositive()
                    .isLessThan(content.indexOf("Carlos Gomez"));
        }

        @Test
        void shouldThrowNoAppointmentsExceptionWhenThereAreNoAppointments() {
            LocalDate date = LocalDate.now().minusDays(1);
            when(appointmentDataClient.getAllAppointmentsByDate(date)).thenReturn(List.of());

            assertThatThrownBy(() -> service.exportScheduler(new SchedulerRequestDto(date, ExportFormat.CSV)))
                    .isInstanceOf(NoAppointmentsTodayException.class)
                    .hasMessage("No hay citas registradas para el día " + date);
        }
    }
}
