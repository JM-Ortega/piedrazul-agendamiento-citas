package co.edu.unicauca.piedrazul.backend.shared;

import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Copia de una excepción para el log: conserva la clase y los marcos de cada causa, sin sus
 * mensajes ni sus excepciones suprimidas.
 *
 * <p>El mensaje de una excepción inesperada no es de confianza: puede traer el valor rechazado de
 * una petición, el detalle de un error de la base de datos o datos del proveedor. El SQLState sí se
 * conserva, porque identifica el tipo de fallo; solo si tiene la forma estándar.
 */
final class SanitizedThrowable extends RuntimeException {

    private static final int MAX_CHAIN_LENGTH = 16;
    private static final Pattern SQL_STATE = Pattern.compile("[0-9A-Z]{5}");

    private SanitizedThrowable(Throwable source, Set<Throwable> visited) {
        super(describe(source), nextCause(source, visited), false, true);
        setStackTrace(source.getStackTrace());
    }

    static SanitizedThrowable of(Throwable source) {
        return new SanitizedThrowable(source, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static String describe(Throwable source) {
        String type = source.getClass().getName();

        if (source instanceof SQLException sqlException
                && sqlException.getSQLState() != null
                && SQL_STATE.matcher(sqlException.getSQLState()).matches()) {
            return type + " [SQLState=" + sqlException.getSQLState() + "]";
        }

        return type;
    }

    private static SanitizedThrowable nextCause(Throwable source, Set<Throwable> visited) {
        visited.add(source);
        Throwable cause = source.getCause();

        if (cause == null || visited.contains(cause) || visited.size() >= MAX_CHAIN_LENGTH) {
            return null;
        }

        return new SanitizedThrowable(cause, visited);
    }
}
