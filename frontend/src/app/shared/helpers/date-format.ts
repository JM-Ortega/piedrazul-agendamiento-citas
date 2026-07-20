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

const DAY_NAMES_ES_SHORT = ['Dom', 'Lun', 'Mar', 'Mié', 'Jue', 'Vie', 'Sáb'];

/**
 * Devuelve la abreviatura de 3 letras del mes a partir de una fecha 'YYYY-MM-DD'.
 */
export function getMonthShort(dateStr: string): string {
  const month = parseInt(dateStr.split('-')[1], 10) - 1;
  return MONTH_NAMES_ES[month]?.slice(0, 3) ?? '';
}

/**
 * Devuelve la fecha en formato ej: 'Lun 20 de julio de 2026'
 */
export function formatLongDateEs(dateStr: string): string {
  const date = new Date(dateStr + 'T12:00:00');
  const dayName = DAY_NAMES_ES_SHORT[date.getDay()];
  const day = date.getDate();
  const month = MONTH_NAMES_ES[date.getMonth()];
  const year = date.getFullYear();
  return `${dayName} ${day} de ${month} de ${year}`;
}
