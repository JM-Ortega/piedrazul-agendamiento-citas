const MONTH_NAMES_ES = [
  'enero',
  'febrero',
  'marzo',
  'abril',
  'mayo',
  'junio',
  'julio',
  'agosto',
  'septiembre',
  'octubre',
  'noviembre',
  'diciembre',
];

/**
 * Devuelve la abreviatura de 3 letras del mes a partir de una fecha 'YYYY-MM-DD'.
 */
export function getMonthShort(dateStr: string): string {
  const month = parseInt(dateStr.split('-')[1], 10) - 1;
  return MONTH_NAMES_ES[month]?.slice(0, 3) ?? '';
}
