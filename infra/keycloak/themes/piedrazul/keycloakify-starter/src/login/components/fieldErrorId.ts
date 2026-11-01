/** Id del mensaje de error de un campo; mismo patrón que las páginas por defecto de Keycloakify. */
export function getFieldErrorId(inputId: string) {
    return `input-error-${inputId}`;
}
