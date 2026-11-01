package co.edu.unicauca.piedrazul.backend.user.application.bootstrap;

/**
 * Resultado de un intento de bootstrap del primer administrador de negocio. Ninguno es
 * prueba de que una persona haya iniciado sesión.
 *
 * <p>Dos propiedades derivadas, que usan el arranque y el despliegue:
 * <ul>
 *   <li>{@link #successful()}: solo {@code CREATED} y {@code ALREADY_LINKED_READY} cuentan
 *   como bootstrap listo; el despliegue falla con cualquier otro.</li>
 *   <li>{@link #abortsStartup()}: fail-closed. Todo resultado no exitoso impide arrancar el
 *   backend, salvo {@code ALREADY_LINKED_NOT_READY} (identidad conocida cuya autoridad se
 *   cambió de forma administrativa: ver su documentación).</li>
 * </ul>
 */
public enum AdminBootstrapOutcome {

    /** Cuenta nueva creada por este intento, vinculada, con ADMIN y activada; primer login pendiente. */
    CREATED,

    /**
     * La cuenta y la persona configuradas ya estaban vinculadas, y se confirmó el estado del
     * bootstrap: cuenta activa, ADMIN efectivo, ningún otro titular directo de ADMIN y el mismo
     * tipo de documento configurado. No se escribió nada. No prueba un login.
     */
    ALREADY_LINKED_READY,

    /**
     * La cuenta y la persona configuradas ya estaban vinculadas, pero el estado no es el del
     * bootstrap confirmado ({@code reasons}: cuenta desactivada, sin ADMIN efectivo, otros
     * titulares de ADMIN, o tipo de documento distinto del configurado). No se escribió ni
     * reparó nada: pueden ser decisiones administrativas posteriores.
     *
     * <p>No impide el arranque (no hay identidad ajena ni ambigua, y volver a arrancar no
     * cambiaría nada), pero no es un bootstrap listo: el despliegue falla hasta que una persona
     * decida (deshabilitar el bootstrap o restituir a mano).
     */
    ALREADY_LINKED_NOT_READY,

    /** Existen identidades o autoridad ADMIN que no se pueden adoptar. No se escribió nada. */
    CONFLICT,

    /** Entradas ausentes o inválidas. No se hizo ninguna lectura ni escritura de identidad. */
    INVALID_INPUT,

    /** Otro proceso tiene el bootstrap en curso. No se escribió nada. */
    BUSY,

    /**
     * Falló sin efectos conocidos (lectura fallida, incluida la observación de una identidad
     * vinculada, o creación rechazada). Reintentable.
     */
    FAILED_NO_CHANGES,

    /** Falló después de crear la cuenta; la cuenta propia se eliminó y se confirmó. Reintentable. */
    FAILED_COMPENSATED,

    /**
     * Estado parcial o incierto: puede existir una cuenta o persona de este intento. No se
     * borra ni repara nada automáticamente; requiere inspección autorizada.
     */
    FAILED_NEEDS_INSPECTION;

    public boolean successful() {
        return this == CREATED || this == ALREADY_LINKED_READY;
    }

    public boolean abortsStartup() {
        return !successful() && this != ALREADY_LINKED_NOT_READY;
    }
}
