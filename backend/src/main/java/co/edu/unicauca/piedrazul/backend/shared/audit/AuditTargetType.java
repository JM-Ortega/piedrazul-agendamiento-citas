package co.edu.unicauca.piedrazul.backend.shared.audit;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tipos de objeto afectado que se graban en {@code target_entity_type}. Es el catálogo que
 * recibe el frontend para el filtro {@code targetEntityType} de la consulta de auditoría.
 *
 * <p>Son constantes {@code String} para poder usarlas en {@link Auditable#targetEntityType()}.
 * Un tipo nuevo debe agregarse aquí; {@code AuditTargetTypeCatalogTest} lo exige.
 */
public final class AuditTargetType {

    public static final String CITA = "Cita";
    public static final String CONTROL_MEDICO = "ControlMedico";
    public static final String DOCTOR = "Doctor";
    public static final String PACIENTE = "Paciente";
    public static final String USUARIO = "Usuario";

    /** Código grabado → nombre para mostrar, en el orden en que se muestran. */
    public static final Map<String, String> NAMES;

    static {
        Map<String, String> names = new LinkedHashMap<>();
        names.put(CITA, "Cita");
        names.put(CONTROL_MEDICO, "Control médico");
        names.put(DOCTOR, "Doctor");
        names.put(PACIENTE, "Paciente");
        names.put(USUARIO, "Usuario");
        NAMES = Collections.unmodifiableMap(names);
    }

    private AuditTargetType() { }
}
