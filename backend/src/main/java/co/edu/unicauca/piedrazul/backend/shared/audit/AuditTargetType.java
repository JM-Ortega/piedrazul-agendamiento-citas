package co.edu.unicauca.piedrazul.backend.shared.audit;

/**
 * Tipos de objeto afectado que se graban en {@code target_entity_type}.
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

    private AuditTargetType() { }
}
