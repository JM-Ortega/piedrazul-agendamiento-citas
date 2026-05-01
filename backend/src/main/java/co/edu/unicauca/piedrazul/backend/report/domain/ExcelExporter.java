package co.edu.unicauca.piedrazul.backend.report.domain;

import co.edu.unicauca.piedrazul.backend.report.dtos.AppointmentReportRow;
import co.edu.unicauca.piedrazul.backend.report.dtos.DailyReportDto;
import co.edu.unicauca.piedrazul.backend.report.dtos.ReportColumn;
import co.edu.unicauca.piedrazul.backend.report.util.ReportRowMapper;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
                sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, columnas.size() - 1));

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
        return style;
    }

    private CellStyle crearEstiloDatos(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }


}
