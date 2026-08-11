export interface DocumentIdRule {
  regex: RegExp;
  message: string;
}

export const DOCUMENT_ID_RULES: Record<string, DocumentIdRule> = {
  CEDULA: {
    regex: /^\d{6,10}$/,
    message: 'La cédula debe tener entre 6 y 10 dígitos numéricos.',
  },
  TARJETA_IDENTIDAD: {
    regex: /^\d{10}$/,
    message: 'La tarjeta de identidad debe tener exactamente 10 dígitos.',
  },
  REGISTRO_NACIMIENTO: {
    regex: /^\d{8,20}$/,
    message: 'El registro de nacimiento debe tener entre 8 y 20 dígitos.',
  },
  PASAPORTE: {
    regex: /^[a-zA-Z0-9]{6,9}$/,
    message: 'El pasaporte debe tener entre 6 y 9 caracteres alfanuméricos.',
  },
};

/**
 * Valida un número de documento de identidad según el tipo seleccionado.
 *
 * @param value - Número de documento ingresado por el usuario.
 * @param documentType - Tipo de documento (ej. 'CEDULA', 'PASAPORTE').
 * @returns Mensaje de error si es inválido, o `undefined` si es válido.
 */
export function validateDocumentId(
  value: string,
  documentType: string
): string | undefined {
  const trimmed = value.trim();
  if (!trimmed) {
    return 'El documento de identidad es obligatorio.';
  }

  const rule = DOCUMENT_ID_RULES[documentType];
  if (!rule) {
    // Tipo de documento aún no seleccionado o desconocido: validación genérica.
    return !/^\d{5,15}$/.test(trimmed)
      ? 'Debe contener entre 5 y 15 dígitos numéricos.'
      : undefined;
  }

  return rule.regex.test(trimmed) ? undefined : rule.message;
}

/**
 * Longitud máxima permitida para el documento, según el tipo seleccionado.
 * Usado para limitar el input en tiempo real (maxLength del átomo).
 *
 * @param documentType - Tipo de documento.
 * @returns Longitud máxima, o 15 como valor por defecto genérico.
 */
export function getDocumentIdMaxLength(documentType: string): number {
  const rule = DOCUMENT_ID_RULES[documentType];
  if (!rule) return 15;
  // Extrae el número máximo del regex de forma aproximada según el tipo conocido.
  const maxLengths: Record<string, number> = {
    CEDULA: 10,
    TARJETA_IDENTIDAD: 10,
    REGISTRO_NACIMIENTO: 20,
    PASAPORTE: 9,
  };
  return maxLengths[documentType] ?? 15;
}

/**
 * Regla de sanitización a aplicar en el input, según el tipo de documento.
 * Pasaporte permite letras y números; el resto solo dígitos.
 *
 * @param documentType - Tipo de documento.
 * @returns 'numeric' o 'alphanumeric'.
 */
export function getDocumentIdSanitize(
  documentType: string
): 'numeric' | 'alphanumeric' {
  return documentType === 'PASAPORTE' ? 'alphanumeric' : 'numeric';
}
