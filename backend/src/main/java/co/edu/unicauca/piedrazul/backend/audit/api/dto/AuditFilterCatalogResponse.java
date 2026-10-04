package co.edu.unicauca.piedrazul.backend.audit.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** Opciones y límites de los filtros de {@code GET /api/audit}. */
@Schema(description = "Opciones y límites de los filtros de la consulta de auditoría")
public record AuditFilterCatalogResponse(
        @Schema(description = "Valores del filtro `moduleCode`, ordenados por nombre")
        List<Option> modules,

        @Schema(description = "Valores del filtro `action`, ordenados por módulo y nombre. `moduleCode` permite "
                + "limitar las acciones al módulo elegido")
        List<AuditActionCatalogResponse> actions,

        @Schema(description = "Valores del filtro `outcome`")
        List<Option> outcomes,

        @Schema(description = "Días máximos que puede abarcar el rango cuando se envían `from` y `to`, "
                + "contando los dos", example = "90")
        int maxRangeDays,

        @Schema(description = "Caracteres máximos del filtro `search`", example = "100")
        int maxSearchLength
) {
    @Schema(description = "Opción de un filtro")
    public record Option(
            @Schema(description = "Valor que se envía en el filtro", example = "PACIENTES")
            String code,

            @Schema(description = "Nombre para mostrar", example = "Pacientes")
            String name
    ) { }
}
