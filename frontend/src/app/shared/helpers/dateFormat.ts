import { parseLocalDateString } from './transformDateLocal';

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
 * Devuelve la abreviatura de 3 letras del mes a partir de una fecha
 * 'YYYY-MM-DD' o de un Date.
 */
export function getMonthShort(date: string | Date): string {
  const month =
    typeof date === 'string'
      ? parseInt(date.split('-')[1], 10) - 1
      : date.getMonth();
  return MONTH_NAMES_ES[month]?.slice(0, 3) ?? '';
}

/**
 * Devuelve la abreviatura de 3 letras, en minúscula, del día de la semana.
 */
export function getDayShort(date: Date): string {
  return DAY_NAMES_ES_SHORT[date.getDay()].slice(0, 3).toLowerCase();
}

/**
 * Fecha corta con día de 2 dígitos, mes abreviado y año.
 */
export function formatShortDateEs(date: Date): string {
  const day = String(date.getDate()).padStart(2, '0');
  return `${day} ${getMonthShort(date)} ${date.getFullYear()}`;
}

/**
 * Día y mes abreviado, sin año.
 */
export function formatDayMonthEs(date: Date): string {
  return `${date.getDate()} ${getMonthShort(date)}`;
}

/**
 * Rango de fechas 'YYYY-MM-DD' (inclusivo) compacto: omite el mes y el año
 * del inicio cuando coinciden con los del fin.
 */
export function formatDateRangeEs(startIso: string, endIso: string): string {
  const s = parseLocalDateString(startIso);
  const e = parseLocalDateString(endIso);
  const endText = `${formatDayMonthEs(e)} ${e.getFullYear()}`;
  if (s.getTime() === e.getTime()) return endText;
  if (s.getFullYear() !== e.getFullYear()) {
    return `${formatDayMonthEs(s)} ${s.getFullYear()} – ${endText}`;
  }
  if (s.getMonth() !== e.getMonth()) {
    return `${formatDayMonthEs(s)} – ${endText}`;
  }
  return `${s.getDate()} – ${endText}`;
}

/**
 * Rango de fechas con el día de la semana abreviado, sin año. Omite el mes
 * del inicio cuando coincide con el del fin.
 */
export function formatWeekdayRangeEs(start: Date, end: Date): string {
  const endText = `${getDayShort(end)} ${formatDayMonthEs(end)}`;
  if (start.getTime() === end.getTime()) return endText;
  const startText =
    start.getMonth() !== end.getMonth()
      ? formatDayMonthEs(start)
      : String(start.getDate());
  return `${getDayShort(start)} ${startText} – ${endText}`;
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
