package co.edu.unicauca.piedrazul.backend.report.csv;

import co.edu.unicauca.piedrazul.backend.report.dtos.AppointmentReportRow;
import co.edu.unicauca.piedrazul.backend.report.dtos.DailyReportDto;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

@Component
public class CsvExporter {

    private static final String[] HEADERS = {
            "Nombre Médico", "Nombre Paciente",
            "Fecha", "Hora Inicio", "Estado"
    };

    public byte[] export(DailyReportDto report) {

        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             OutputStreamWriter writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
             CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT)) {

            // BOM para compatibilidad con Excel
            out.write(0xEF);
            out.write(0xBB);
            out.write(0xBF);

            printer.printRecord(
                    "Dr(a). " + report.doctorFullName()
                            + " | Total citas: " + report.total()
                            + " | Fecha: " + report.date()
            );
            printer.printRecord((Object[]) HEADERS);

            for (AppointmentReportRow row : report.rows()) {
                printer.printRecord(
                        report.doctorFullName(),
                        row.patientFullName(),
                        row.date(),
                        row.startTime(),
                        "AGENDADA"
                );
            }

            writer.flush();
            return out.toByteArray();

        } catch (IOException e) {
            throw new RuntimeException("Error al generar el CSV", e);
        }
    }

}


