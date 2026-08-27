export type SanitizeRuleForDocument = 'numeric' | 'alphanumeric';

export interface DocumentRule {
  min: number;
  max: number;
  pattern: RegExp;
  sanitize: SanitizeRuleForDocument;
  label: string;
}

export const DOCUMENT_RULES: Record<string, DocumentRule> = {
  TARJETA_IDENTIDAD: {
    min: 10,
    max: 11,
    pattern: /^\d+$/,
    sanitize: 'numeric',
    label: 'La tarjeta de identidad',
  },
  CEDULA: {
    min: 6,
    max: 10,
    pattern: /^\d+$/,
    sanitize: 'numeric',
    label: 'La cédula',
  },
  REGISTRO_NACIMIENTO: {
    min: 8,
    max: 20,
    pattern: /^\d+$/,
    sanitize: 'numeric',
    label: 'El registro de nacimiento',
  },
  PASAPORTE: {
    min: 6,
    max: 9,
    pattern: /^[a-zA-Z0-9]+$/,
    sanitize: 'alphanumeric',
    label: 'El pasaporte',
  },
};

export const DEFAULT_DOCUMENT_MAX_LENGTH = 20;

/** Devuelve un mensaje de error si el documento no cumple la regla del tipo dado, o '' si es válido. */
export function validateDocumentForType(
  type: string,
  identification: string
): string {
  const rule = DOCUMENT_RULES[type];
  if (!rule || !identification) return '';

  if (!rule.pattern.test(identification)) {
    return rule.sanitize === 'alphanumeric'
      ? `${rule.label} solo debe contener letras y números`
      : `${rule.label} solo debe contener números`;
  }

  if (identification.length < rule.min || identification.length > rule.max) {
    return rule.min === rule.max
      ? `${rule.label} debe tener exactamente ${rule.min} dígitos`
      : `${rule.label} debe tener entre ${rule.min} y ${rule.max} caracteres`;
  }

  return '';
}

/**
 * Longitud máxima permitida para el documento, según el tipo seleccionado.
 * Usado para limitar el input en tiempo real (maxLength del átomo).
 */
export function getDocumentIdMaxLength(documentType: string): number {
  return DOCUMENT_RULES[documentType]?.max ?? DEFAULT_DOCUMENT_MAX_LENGTH;
}

/**
 * Regla de sanitización a aplicar en el input, según el tipo de documento.
 */
export function getDocumentIdSanitize(
  documentType: string
): SanitizeRuleForDocument {
  return DOCUMENT_RULES[documentType]?.sanitize ?? 'numeric';
}
