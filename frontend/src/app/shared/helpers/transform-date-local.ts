/**
 * Convierte un string 'yyyy-mm-dd' a Date en hora LOCAL, evitando el
 * corrimiento de un día que produce `new Date(string)` al interpretar
 * fechas ISO sin hora como medianoche UTC.
 */
export function parseLocalDateString(value: string): Date {
  const match = value.match(/^(\d{4})-(\d{2})-(\d{2})$/);
  if (match) {
    const [, y, m, d] = match;
    return new Date(Number(y), Number(m) - 1, Number(d));
  }
  return new Date(value);
}

/** Convierte un Date a string 'yyyy-mm-dd' en hora LOCAL. */
export function toIsoDateString(date: Date): string {
  const y = date.getFullYear();
  const m = String(date.getMonth() + 1).padStart(2, '0');
  const d = String(date.getDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
}
