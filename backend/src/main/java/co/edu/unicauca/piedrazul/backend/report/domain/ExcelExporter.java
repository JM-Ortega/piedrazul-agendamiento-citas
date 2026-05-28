package co.edu.unicauca.piedrazul.backend.report.domain;

import co.edu.unicauca.piedrazul.backend.report.dtos.*;
import co.edu.unicauca.piedrazul.backend.report.util.ReportRowMapper;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

@Component
public class ExcelExporter {

    public final byte[] export(DailyReportDto report, List<ReportColumn> columnas) {
            try (XSSFWorkbook workbook = new XSSFWorkbook();
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {

                Sheet sheet = workbook.createSheet("Citas del día");

                // Estilos
                CellStyle headerStyle = crearEstiloEncabezado(workbook);
                CellStyle titleStyle  = crearEstiloTitulo(workbook);
                CellStyle dataStyle   = crearEstiloDatos(workbook);

                // Fila título del grupo
                Row titulo = sheet.createRow(0);
                Cell tituloCell = titulo.createCell(0);
                tituloCell.setCellValue(
                        "Dr(a). " + report.doctorFullName()
                                + "  |  Total citas: " + report.total()
                                + "  |  Fecha: " + report.date()
                );
                tituloCell.setCellStyle(titleStyle);
                mergeIfNeeded(sheet, 0, 0, 0, columnas.size() - 1);

                // Fila de cabeceras
                Row cabecera = sheet.createRow(1);
                for (int i = 0; i < columnas.size(); i++) {
                    Cell cell = cabecera.createCell(i);
                    cell.setCellValue(columnas.get(i).getEtiqueta());
                    cell.setCellStyle(headerStyle);
                }

                // Filas de datos
                int rowNum = 2;
                for (AppointmentReportRow row : report.rows()) {
                    Row fila = sheet.createRow(rowNum++);
                    List<Object> valores = ReportRowMapper.extraerValores(row, report, columnas);
                    for (int i = 0; i < valores.size(); i++) {
                        Cell cell = fila.createCell(i);
                        cell.setCellValue(valores.get(i) != null ? valores.get(i).toString() : "");
                        cell.setCellStyle(dataStyle);
                    }
                }

                // Autoajustar ancho de columnas
                for (int i = 0; i < columnas.size(); i++) {
                    sheet.autoSizeColumn(i);
                }

                workbook.write(out);
                return out.toByteArray();

            } catch (IOException e) {
                throw new RuntimeException("Error al generar el Excel", e);
            }
    }

    public byte[] exportScheduler(List<DoctorDailyScheduleDto> schedules, LocalDate date, boolean hasAvailableSlots) {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            Sheet sheet = workbook.createSheet("Agenda " + date);

            CellStyle headerStyle = crearEstiloEncabezado(workbook);
            CellStyle dataStyle   = crearEstiloDatos(workbook);

            int startRow = 0;

            if (hasAvailableSlots) {
                CellStyle warningStyle = crearEstiloAdvertencia(workbook);
                Row warningRow = sheet.createRow(startRow++);
                warningRow.setHeightInPoints(40);
                Cell warningCell = warningRow.createCell(0);
                warningCell.setCellValue(
                        "⚠ ADVERTENCIA: Aún existen horarios disponibles para esta fecha. " +
                                "Este documento puede estar sujeto a desactualizaciones."
                );
                warningCell.setCellStyle(warningStyle);
                mergeIfNeeded(sheet, 0, 0, 0, schedules.size() - 1);
                startRow++; // fila en blanco separadora
            }

            // Fila título principal
            CellStyle titleStyle = crearEstiloTitulo(workbook);
            int totalCitas = schedules.stream().mapToInt(s -> s.patientNames().size()).sum();

            Row titulo = sheet.createRow(startRow++);
            titulo.setHeightInPoints(30);
            Cell tituloCell = titulo.createCell(0);
            tituloCell.setCellValue(
                    "Agenda de Citas por Médico" +
                            "  |  Fecha: " + date +
                            "  |  Total médicos: " + schedules.size() +
                            "  |  Total citas: " + totalCitas
            );
            tituloCell.setCellStyle(titleStyle);
            mergeIfNeeded(sheet, startRow - 1, startRow - 1, 0, schedules.size() - 1);

            startRow++; // fila en blanco entre título y encabezados de médicos

            // Fila 0 — nombres de médicos (encabezados de columna)
            Row headerRow = sheet.createRow(startRow++);
            for (int col = 0; col < schedules.size(); col++) {
                Cell cell = headerRow.createCell(col);
                cell.setCellValue(schedules.get(col).doctorName());
                cell.setCellStyle(headerStyle);
            }

            // Filas siguientes — nombres de pacientes por columna
            int maxPatients = schedules.stream()
                    .mapToInt(s -> s.patientNames().size())
                    .max()
                    .orElse(0);

            // Filas de pacientes — corregido
            for (int i = 0; i < maxPatients; i++) {
                Row row = sheet.createRow(startRow + i);  // relativo a startRow
                for (int col = 0; col < schedules.size(); col++) {
                    List<String> patients = schedules.get(col).patientNames();
                    Cell cell = row.createCell(col);
                    if (i < patients.size()) {
                        cell.setCellValue(patients.get(i));
                    }
                    cell.setCellStyle(dataStyle);
                }
            }

            // Autoajustar ancho
            for (int col = 0; col < schedules.size(); col++) {
                sheet.autoSizeColumn(col);
            }

            workbook.write(out);
            return out.toByteArray();

        } catch (IOException e) {
            throw new RuntimeException("Error al generar el Excel del agendador", e);
        }
    }

    // Método helper para agregar al final de la clase
    private void mergeIfNeeded(Sheet sheet, int firstRow, int lastRow, int firstCol, int lastCol) {
        if (firstCol < lastCol || firstRow < lastRow) {
            sheet.addMergedRegion(new CellRangeAddress(firstRow, lastRow, firstCol, lastCol));
        }
    }


    private CellStyle crearEstiloEncabezado(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREEN.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setBorderBottom(BorderStyle.THIN);
        return style;
    }

    private CellStyle crearEstiloTitulo(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 12);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.LIGHT_GREEN.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setWrapText(true);
        return style;
    }

    private CellStyle crearEstiloDatos(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }

    private CellStyle crearEstiloAdvertencia(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.DARK_RED.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setWrapText(true);
        return style;
    }

}
