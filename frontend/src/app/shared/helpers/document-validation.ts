export type SanitizeRuleForDocument = 'numeric' | 'alphanumeric';

export interface DocumentRule {
  min: number;
  max: number;
  pattern: RegExp;
  sanitize: SanitizeRuleForDocument;
}

export const DOCUMENT_RULES: Record<string, DocumentRule> = {
  TARJETA_IDENTIDAD: {
    min: 10,
    max: 10,
    pattern: /^\d+$/,
    sanitize: 'numeric',
  },
  CEDULA: { min: 6, max: 10, pattern: /^\d+$/, sanitize: 'numeric' },
  REGISTRO_NACIMIENTO: {
    min: 8,
    max: 20,
    pattern: /^\d+$/,
    sanitize: 'numeric',
  },
  PASAPORTE: {
    min: 6,
    max: 9,
    pattern: /^[a-zA-Z0-9]+$/,
    sanitize: 'alphanumeric',
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
    return type === 'PASAPORTE'
      ? 'El pasaporte solo debe contener letras y números'
      : 'El documento solo debe contener números';
  }

  if (identification.length < rule.min || identification.length > rule.max) {
    return rule.min === rule.max
      ? `El documento debe tener exactamente ${rule.min} dígitos`
      : `El documento debe tener entre ${rule.min} y ${rule.max} caracteres`;
  }

  return '';
}
