package co.edu.unicauca.piedrazul.backend.report.csv;

import co.edu.unicauca.piedrazul.backend.report.dtos.DailyReportDto;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

public class CsvExporter {

    private static final String[] HEADERS = {
            "Nombre Médico", "Nombre Paciente",
            "Fecha", "Hora Inicio", "Estado"
    };

    public byte[] export(DailyReportDto report) {

    }

}
