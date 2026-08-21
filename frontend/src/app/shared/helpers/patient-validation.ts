import { parseLocalDateString } from './transform-date-local';

/**
 * Tipos de documento que, por definición, corresponden a un menor de edad.
 */
export const MINOR_DOCUMENT_TYPES = new Set([
  'TARJETA_IDENTIDAD',
  'REGISTRO_NACIMIENTO',
]);

/** Longitud exacta esperada para números de teléfono (celular colombiano a 10 dígitos). */
export const PHONE_LENGTH = 10;

/** Longitud mínima por defecto para nombres y apellidos. */
export const NAME_MIN_DEFAULT = 2;

/** Longitud máxima por defecto para nombres y apellidos. */
export const NAME_MAX_DEFAULT = 30;

/** Longitud máxima por defecto para el correo electrónico. */
export const EMAIL_MAX_DEFAULT = 50;

const VALID_NAME_REGEX = /^[a-zA-Z\s]+$/;
const VALID_EMAIL_REGEX = /^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$/;
const INVALID_EMAIL_CHARS = /['"<>()[\]\\,;:{}|^~`!#$%&*=?/]/;

/**
 * Calcula la edad en años cumplidos a partir de una fecha de nacimiento.
 *
 * @param birthDate - Fecha de nacimiento como objeto `Date`.
 * @returns Edad en años cumplidos.
 */
export function calcAge(birthDate: Date): number {
  const today = new Date();
  let age = today.getFullYear() - birthDate.getFullYear();
  const m = today.getMonth() - birthDate.getMonth();
  if (m < 0 || (m === 0 && today.getDate() < birthDate.getDate())) age--;
  return age;
}

/**
 * Indica si, según una fecha de nacimiento en formato `'yyyy-mm-dd'`, la persona es menor de edad.
 *
 * @param value - Fecha de nacimiento en formato `'yyyy-mm-dd'`.
 */
export function isMinorByBirthDate(value: string): boolean {
  if (!value) return false;
  return calcAge(parseLocalDateString(value)) < 18;
}

/**
 * Indica si una persona debe considerarse menor de edad, combinando dos señales posibles:
 * el tipo de documento y la fecha de nacimiento. Útil en formularios donde se conoce
 * el tipo de documento (registro de paciente nuevo); si no se conoce, pasar `''`.
 *
 * @param documentType - Tipo de documento de identidad. Puede ir vacío si no aplica.
 * @param birthDate - Fecha de nacimiento en formato `'yyyy-mm-dd'`.
 */
export function isMinorPatient(
  documentType: string,
  birthDate: string
): boolean {
  return (
    MINOR_DOCUMENT_TYPES.has(documentType) ||
    (birthDate ? isMinorByBirthDate(birthDate) : false)
  );
}

/**
 * Valida un nombre o apellido: obligatoriedad, longitud y que solo contenga letras y espacios.
 *
 * @param value - Valor ingresado por el usuario.
 * @param options.min - Longitud mínima permitida (por defecto {@link NAME_MIN_DEFAULT}).
 * @param options.max - Longitud máxima permitida (por defecto {@link NAME_MAX_DEFAULT}).
 * @returns Mensaje de error, o cadena vacía si es válido.
 */
export function validateName(
  value: string,
  options?: { min?: number; max?: number }
): string {
  const min = options?.min ?? NAME_MIN_DEFAULT;
  const max = options?.max ?? NAME_MAX_DEFAULT;

  const trimmed = value?.trim() ?? '';
  if (!trimmed) return 'Este campo es obligatorio';
  if (trimmed.length < min) return `Debe ingresar al menos ${min} caracteres`;
  if (trimmed.length > max)
    return `Se permiten ingresar máximo ${max} caracteres`;
  if (!VALID_NAME_REGEX.test(trimmed))
    return 'Solo se permiten letras y espacios';
  return '';
}

/**
 * Valida un número de teléfono de 10 dígitos.
 *
 * @param value - Valor ingresado por el usuario.
 * @param required - Si es `true`, el campo vacío también es un error. Si es `false`, un campo vacío se considera válido
 * (el resto de reglas solo aplican cuando sí viene diligenciado).
 * @returns Mensaje de error, o cadena vacía si es válido.
 */
export function validatePhone(value: string, required: boolean): string {
  const trimmed = value?.trim() ?? '';
  if (!trimmed) return required ? 'Este campo es obligatorio' : '';
  if (!new RegExp(`^[0-9]{${PHONE_LENGTH}}$`).test(trimmed)) {
    return `Ingrese un número válido de ${PHONE_LENGTH} dígitos`;
  }
  return '';
}

/**
 * Valida una fecha de nacimiento: obligatoriedad y que no sea una fecha futura o de hoy.
 * Si se indica `documentType`, además valida coherencia entre la edad resultante y el tipo de documento.
 * Si no se indica `documentType` (o viene vacío), esa validación de coherencia se omite — útil para
 * formularios donde el tipo de documento no se pide.
 *
 * @param value - Fecha de nacimiento en formato `'yyyy-mm-dd'`.
 * @param documentType - Tipo de documento de identidad, opcional.
 * @returns Mensaje de error, o cadena vacía si es válida.
 */
export function validateBirthDate(value: string, documentType = ''): string {
  if (!value) return 'Ingrese una fecha de nacimiento válida';

  const today = new Date();
  today.setHours(0, 0, 0, 0);
  const input = parseLocalDateString(value);
  input.setHours(0, 0, 0, 0);

  if (input >= today) return 'La fecha de nacimiento debe ser anterior a hoy';

  if (!documentType) return '';

  if (documentType === 'CEDULA') {
    const age = calcAge(input);
    if (age < 18) {
      return 'La fecha ingresada indica que el paciente es menor de edad. Para Cédula el paciente debe tener 18 años o más.';
    }
  }

  if (MINOR_DOCUMENT_TYPES.has(documentType)) {
    const age = calcAge(input);
    if (age >= 18) {
      return 'La fecha ingresada indica que el paciente es mayor de edad. El tipo de documento seleccionado no es válido.';
    }
  }

  return '';
}

/**
 * Valida un correo electrónico opcional: longitud, caracteres no permitidos y estructura general.
 * Un valor vacío se considera válido, ya que el correo es un campo opcional.
 *
 * @param value - Correo ingresado por el usuario.
 * @param max - Longitud máxima permitida (por defecto {@link EMAIL_MAX_DEFAULT}).
 * @returns Mensaje de error, o cadena vacía si es válido.
 */
export function validateEmail(value: string, max = EMAIL_MAX_DEFAULT): string {
  if (!value) return '';
  if (value.length > max)
    return `El correo no puede superar los ${max} caracteres`;
  if (INVALID_EMAIL_CHARS.test(value))
    return 'No se permiten caracteres especiales como \', ", <, >, (, ), [, ], etc.';
  if (!VALID_EMAIL_REGEX.test(value))
    return 'La estructura del correo no es válida. Ejemplo: nombre@dominio.com';
  return '';
}

/**
 * Valida el teléfono del acudiente: formato de 10 dígitos si viene diligenciado
 * y obligatoriedad cuando la persona es menor de edad.
 *
 * @param value - Teléfono del acudiente ingresado.
 * @param isMinor - Si la persona es menor de edad, para decidir si el campo es obligatorio.
 * @returns Mensaje de error, o cadena vacía si es válido.
 */
export function validateGuardianPhone(value: string, isMinor: boolean): string {
  const trimmed = value?.trim() ?? '';
  if (trimmed) {
    const formatErr = validatePhone(trimmed, false);
    if (formatErr) return formatErr;
  }

  if (isMinor && !trimmed) {
    return 'El celular del acudiente es obligatorio para menores de 18 años';
  }
  return '';
}
