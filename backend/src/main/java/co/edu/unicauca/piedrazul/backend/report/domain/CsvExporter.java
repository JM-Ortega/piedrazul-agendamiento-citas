package co.edu.unicauca.piedrazul.backend.report.domain;

import co.edu.unicauca.piedrazul.backend.report.dtos.AppointmentReportRow;
import co.edu.unicauca.piedrazul.backend.report.dtos.DailyReportDto;
import co.edu.unicauca.piedrazul.backend.report.dtos.ReportColumn;
import co.edu.unicauca.piedrazul.backend.report.util.ReportRowMapper;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
public class CsvExporter {


    public final byte[] export(DailyReportDto report, List<ReportColumn> columns) {

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

            //Encabezado dinamico segun columnas elegidas
            printer.printRecord(
                    columns.stream().map(ReportColumn::getEtiqueta).toArray()
            );


            for (AppointmentReportRow row : report.rows()) {
                printer.printRecord(ReportRowMapper.extraerValores(row, report, columns));
            }

            writer.flush();
            return out.toByteArray();

        } catch (IOException e) {
            throw new RuntimeException("Error al generar el CSV", e);
        }
    }



}


