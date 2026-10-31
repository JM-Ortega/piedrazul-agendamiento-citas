package co.edu.unicauca.piedrazul.backend.patients.application;

import co.edu.unicauca.piedrazul.backend.patients.api.dto.internal.PatientData;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.internal.UpdatePatientCommand;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Diferencia entre el estado actual de un paciente y el que pide una edición.
 *
 * <p>Sirve para tres decisiones: no hacer nada si nada cambió, sincronizar la
 * cuenta de acceso solo si cambió algo que ella guarda, y dejar constancia en la
 * auditoría. La bitácora es append-only, por eso {@link #beforeJson()} y
 * {@link #afterJson()} no incluyen datos personales en claro: el documento va
 * enmascarado, los catálogos (tipo de documento, sexo) van tal cual, y el resto
 * solo marca que el campo cambió.
 */
public final class PatientChanges {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CHANGED = "[modificado]";

    private final Map<String, String> before = new LinkedHashMap<>();
    private final Map<String, String> after = new LinkedHashMap<>();
    private boolean identificationChanged;
    private boolean accountDataChanged;

    private PatientChanges() {
    }

    public static PatientChanges between(PatientData current, UpdatePatientCommand next) {
        PatientChanges changes = new PatientChanges();

        if (!Objects.equals(current.identificationType(), next.identificationType())) {
            changes.record("identificationType",
                    String.valueOf(current.identificationType()), String.valueOf(next.identificationType()));
        }

        if (differs(current.identification(), next.identification())) {
            changes.identificationChanged = true;
            changes.accountDataChanged = true;
            changes.record("identification",
                    mask(current.identification()), mask(next.identification()));
        }

        if (differs(current.firstName(), next.firstName())) {
            changes.accountDataChanged = true;
            changes.record("firstName", CHANGED, CHANGED);
        }

        if (differs(current.lastName(), next.lastName())) {
            changes.accountDataChanged = true;
            changes.record("lastName", CHANGED, CHANGED);
        }

        if (differs(current.email(), next.email())) {
            changes.accountDataChanged = true;
            changes.record("email", CHANGED, CHANGED);
        }

        if (differs(current.phone(), next.phone())) {
            changes.record("phone", CHANGED, CHANGED);
        }

        if (!Objects.equals(current.sex(), next.sex())) {
            changes.record("sex", String.valueOf(current.sex()), String.valueOf(next.sex()));
        }

        if (!Objects.equals(current.birthDate(), next.birthDate())) {
            changes.record("birthDate", CHANGED, CHANGED);
        }

        if (differs(current.guardianPhone(), next.guardianPhone())) {
            changes.record("guardianPhone", CHANGED, CHANGED);
        }

        return changes;
    }

    public boolean isEmpty() {
        return before.isEmpty();
    }

    /** El número de documento cambió: hay que validar que nadie más lo use. */
    public boolean identificationChanged() {
        return identificationChanged;
    }

    /** Cambió algo que la cuenta de acceso también guarda: usuario, nombres o correo. */
    public boolean accountDataChanged() {
        return accountDataChanged;
    }

    public String beforeJson() {
        return toJson(before);
    }

    public String afterJson() {
        return toJson(after);
    }

    private void record(String field, String beforeValue, String afterValue) {
        before.put(field, beforeValue);
        after.put(field, afterValue);
    }

    // Nulo y vacío son lo mismo para los datos opcionales (correo, teléfono de familiar).
    private static boolean differs(String a, String b) {
        return !Objects.equals(a == null ? "" : a, b == null ? "" : b);
    }

    private static String mask(String document) {
        if (document == null || document.length() <= 4) {
            return "****";
        }
        return "*".repeat(document.length() - 4) + document.substring(document.length() - 4);
    }

    private static String toJson(Map<String, String> values) {
        try {
            return JSON.writeValueAsString(values);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("No se pudo serializar el detalle de la auditoría", ex);
        }
    }
}
