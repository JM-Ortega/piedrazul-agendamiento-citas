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

const DAY_NAMES_ES_SHORT = [
  'Domingo',
  'Lunes',
  'Martes',
  'Miércoles',
  'Jueves',
  'Viernes',
  'Sábado',
];

/**
 * Devuelve la abreviatura de 3 letras del mes a partir de una fecha 'YYYY-MM-DD'.
 */
export function getMonthShort(dateStr: string): string {
  const month = parseInt(dateStr.split('-')[1], 10) - 1;
  return MONTH_NAMES_ES[month]?.slice(0, 3) ?? '';
}

/**
 * Devuelve la fecha en formato: 'Lun 20 de julio de 2026'
 */
export function formatLongDateEs(date: string | Date): string {
  const dateObj =
    typeof date === 'string' ? new Date(date + 'T12:00:00') : date;

  const dayName = DAY_NAMES_ES_SHORT[dateObj.getDay()];
  const day = dateObj.getDate();
  const month = MONTH_NAMES_ES[dateObj.getMonth()];
  const year = dateObj.getFullYear();

  return `${dayName} ${day} de ${month} de ${year}`;
}
