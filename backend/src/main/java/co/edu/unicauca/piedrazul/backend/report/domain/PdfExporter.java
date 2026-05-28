package co.edu.unicauca.piedrazul.backend.report.domain;

import co.edu.unicauca.piedrazul.backend.report.dtos.AppointmentReportRow;
import co.edu.unicauca.piedrazul.backend.report.dtos.DailyReportDto;
import co.edu.unicauca.piedrazul.backend.report.dtos.DoctorDailyScheduleDto;
import co.edu.unicauca.piedrazul.backend.report.dtos.ReportColumn;
import co.edu.unicauca.piedrazul.backend.report.util.ReportRowMapper;
import com.lowagie.text.*;
import com.lowagie.text.pdf.*;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.List;

@Component
public class PdfExporter {

    // Colores personalizados para el diseño del PDF
    private static final Color COLOR_HEADER_BG  = new Color(0, 153, 76);   // verde oscuro
    private static final Color COLOR_TITLE_BG   = new Color(198, 239, 206); // verde claro
    private static final Color COLOR_ROW_ALT    = new Color(242, 242, 242); // gris claro filas pares
    private static final Color COLOR_BORDER     = new Color(180, 180, 180);
    private static final Color COLOR_WHITE      = Color.WHITE;
    private static final Color COLOR_TEXT_DARK  = new Color(30, 30, 30);

    public final byte[] export(DailyReportDto report, List<ReportColumn> columnas) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            Document doc = new Document(PageSize.A4.rotate(), 30, 30, 40, 30);
            PdfWriter.getInstance(doc, out);
            doc.open();

            //Título principal
            Font fuenteTitulo = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14, COLOR_TEXT_DARK);
            Font fuenteSubtitulo = FontFactory.getFont(FontFactory.HELVETICA, 10, COLOR_TEXT_DARK);

            Paragraph titulo = new Paragraph("Reporte de Citas Diarias", fuenteTitulo);
            titulo.setAlignment(Element.ALIGN_CENTER);
            titulo.setSpacingAfter(4);
            doc.add(titulo);

            Paragraph subtitulo = new Paragraph(
                    "Dr(a). " + report.doctorFullName()
                            + "   |   Fecha: " + report.date()
                            + "   |   Total citas: " + report.total(),
                    fuenteSubtitulo
            );
            subtitulo.setAlignment(Element.ALIGN_CENTER);
            subtitulo.setSpacingAfter(14);
            doc.add(subtitulo);

            //Tabla
            PdfPTable tabla = new PdfPTable(columnas.size());
            tabla.setWidthPercentage(100);
            tabla.setSpacingBefore(4);

            // Anchos relativos por columna (heurística según contenido típico)
            float[] anchos = calcularAnchos(columnas);
            tabla.setWidths(anchos);

            // Fila de encabezados
            Font fuenteHeader = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, COLOR_WHITE);
            for (ReportColumn col : columnas) {
                PdfPCell celda = new PdfPCell(new Phrase(col.getEtiqueta(), fuenteHeader));
                celda.setBackgroundColor(COLOR_HEADER_BG);
                celda.setHorizontalAlignment(Element.ALIGN_CENTER);
                celda.setVerticalAlignment(Element.ALIGN_MIDDLE);
                celda.setPadding(6);
                celda.setBorderColor(COLOR_BORDER);
                tabla.addCell(celda);
            }

            // Filas de datos
            Font fuenteDatos = FontFactory.getFont(FontFactory.HELVETICA, 8, COLOR_TEXT_DARK);
            int numFila = 0;
            for (AppointmentReportRow row : report.rows()) {
                List<Object> valores = ReportRowMapper.extraerValores(row, report, columnas);
                Color bgFila = (numFila % 2 == 0) ? COLOR_WHITE : COLOR_ROW_ALT;

                for (Object valor : valores) {
                    PdfPCell celda = new PdfPCell(
                            new Phrase(valor != null ? valor.toString() : "", fuenteDatos)
                    );
                    celda.setBackgroundColor(bgFila);
                    celda.setHorizontalAlignment(Element.ALIGN_CENTER);
                    celda.setVerticalAlignment(Element.ALIGN_MIDDLE);
                    celda.setPadding(5);
                    celda.setBorderColor(COLOR_BORDER);
                    tabla.addCell(celda);
                }
                numFila++;
            }

            doc.add(tabla);

            //Pie de página con total
            Font fuentePie = FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 8,
                    new Color(120, 120, 120));
            Paragraph pie = new Paragraph("Generado automáticamente  •  Total de registros: "
                    + report.total(), fuentePie);
            pie.setAlignment(Element.ALIGN_RIGHT);
            pie.setSpacingBefore(10);
            doc.add(pie);

            doc.close();
            return out.toByteArray();

        } catch (Exception e) {
            throw new RuntimeException("Error al generar el PDF", e);
        }
    }

    public byte[] exportScheduler(List<DoctorDailyScheduleDto> schedules, LocalDate date, boolean hasAvailableSlots) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            Document doc = new Document(PageSize.A4.rotate(), 30, 30, 40, 30);
            PdfWriter.getInstance(doc, out);
            doc.open();

            // Título principal
            Font fuenteTitulo    = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14, COLOR_TEXT_DARK);
            Font fuenteSubtitulo = FontFactory.getFont(FontFactory.HELVETICA, 10, COLOR_TEXT_DARK);

            Paragraph titulo = new Paragraph("Agenda de Citas por Médico", fuenteTitulo);
            titulo.setAlignment(Element.ALIGN_CENTER);
            titulo.setSpacingAfter(4);
            doc.add(titulo);

            int totalCitas = schedules.stream().mapToInt(s -> s.patientNames().size()).sum();

            Paragraph subtitulo = new Paragraph(
                    "Fecha: " + date + "   |   Total médicos: " + schedules.size()
                            + "   |   Total citas: " + totalCitas,
                    fuenteSubtitulo
            );

            if (hasAvailableSlots) {
                Font fuenteWarning = FontFactory.getFont(
                        FontFactory.HELVETICA_BOLD, 9, new Color(180, 0, 0));
                Paragraph warning = new Paragraph(
                        "⚠ ADVERTENCIA: Aún existen horarios disponibles para esta fecha. " +
                                "Este documento puede estar sujeto a desactualizaciones.",
                        fuenteWarning
                );
                warning.setAlignment(Element.ALIGN_CENTER);
                warning.setSpacingAfter(10);
                doc.add(warning);
            }

            subtitulo.setAlignment(Element.ALIGN_CENTER);
            subtitulo.setSpacingAfter(14);
            doc.add(subtitulo);

            // Tabla — una columna por médico
            PdfPTable tabla = new PdfPTable(schedules.size());
            tabla.setWidthPercentage(100);
            tabla.setSpacingBefore(4);

            // Encabezados — nombres de médicos
            Font fuenteHeader = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, COLOR_WHITE);
            for (DoctorDailyScheduleDto schedule : schedules) {
                PdfPCell celda = new PdfPCell(new Phrase(schedule.doctorName(), fuenteHeader));
                celda.setBackgroundColor(COLOR_HEADER_BG);
                celda.setHorizontalAlignment(Element.ALIGN_CENTER);
                celda.setVerticalAlignment(Element.ALIGN_MIDDLE);
                celda.setPadding(6);
                celda.setBorderColor(COLOR_BORDER);
                tabla.addCell(celda);
            }

            // Filas de pacientes — una por slot, columna por médico
            int maxPatients = schedules.stream()
                    .mapToInt(s -> s.patientNames().size())
                    .max()
                    .orElse(0);

            Font fuenteDatos = FontFactory.getFont(FontFactory.HELVETICA, 8, COLOR_TEXT_DARK);

            for (int i = 0; i < maxPatients; i++) {
                final int index = i;
                Color bgFila = (i % 2 == 0) ? COLOR_WHITE : COLOR_ROW_ALT;

                for (DoctorDailyScheduleDto schedule : schedules) {
                    String nombre = index < schedule.patientNames().size()
                            ? schedule.patientNames().get(index)
                            : "";
                    PdfPCell celda = new PdfPCell(new Phrase(nombre, fuenteDatos));
                    celda.setBackgroundColor(bgFila);
                    celda.setHorizontalAlignment(Element.ALIGN_CENTER);
                    celda.setVerticalAlignment(Element.ALIGN_MIDDLE);
                    celda.setPadding(5);
                    celda.setBorderColor(COLOR_BORDER);
                    tabla.addCell(celda);
                }
            }

            doc.add(tabla);

            // Pie de página
            Font fuentePie = FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 8,
                    new Color(120, 120, 120));
            Paragraph pie = new Paragraph(
                    "Generado automáticamente  •  Total de registros: " + totalCitas,
                    fuentePie
            );
            pie.setAlignment(Element.ALIGN_RIGHT);
            pie.setSpacingBefore(10);
            doc.add(pie);

            doc.close();
            return out.toByteArray();

        } catch (Exception e) {
            throw new RuntimeException("Error al generar el PDF del agendador", e);
        }
    }

    /**
     * Asigna anchos relativos a las columnas según su contenido típico,
     * para que la tabla se vea proporcional sin necesidad de autoSize.
     */
    private float[] calcularAnchos(List<ReportColumn> columnas) {
        float[] anchos = new float[columnas.size()];
        for (int i = 0; i < columnas.size(); i++) {
            anchos[i] = switch (columnas.get(i)) {
                case NOMBRE_PACIENTE  -> 3.0f;
                case NOMBRE_MEDICO    -> 3.0f;
                case ESPECIALIDAD     -> 2.5f;
                case DOCUMENTO_IDENTIDAD -> 2.0f;
                case TELEFONO_PACIENTE   -> 2.0f;
                case FECHA_CITA       -> 1.8f;
                case HORA_CITA        -> 1.4f;
                case ESTADO_CITA      -> 1.8f;
            };
        }
        return anchos;
    }
}