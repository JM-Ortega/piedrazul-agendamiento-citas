package co.edu.unicauca.piedrazul.backend.report.dtos;

public enum ReportColumn {
    //la etiqueta es para el encabezado del archivo
    FECHA_CITA("Fecha de la Cita"),
    HORA_CITA("Hora de la cita"),
    NOMBRE_PACIENTE("Nombre del paciente"),
    DOCUMENTO_IDENTIDAD("Documento"),
    TELEFONO_PACIENTE("Telefono del paciente"),
    NOMBRE_MEDICO("Nombre del Médico"),
    ESPECIALIDAD("Especialidad"),
    ESTADO_CITA("Estado de la Cita");

    private final String etiqueta;

    ReportColumn(String etiqueta) {
        this.etiqueta = etiqueta;
    }

    public String getEtiqueta() {
        return etiqueta;
    }
}
