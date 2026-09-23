/**
 * Convierte una hora en formato 'HH:mm' a minutos totales desde
 * medianoche. Devuelve 0 si el valor es vacío/nulo.
 */
export function timeToMinutes(time: string): number {
  if (!time) return 0;
  const [h, m] = time.split(':').map(Number);
  return h * 60 + m;
}

/**
 * Convierte una hora en formato 24h ("06:00".."13:00") a su etiqueta
 * legible en formato 12h sin sufijo am/pm (ej: "13:00" -> "1:00",
 * "06:30" -> "6:30"), ya que en este sistema el rango de horarios
 * disponibles (6:00 a 13:00) no genera ambigüedad entre am/pm.
 */
export function formatTimeLabel12h(time: string): string {
  const [hStr, m] = time.split(':');
  const h = Number(hStr);
  const displayHour = h > 12 ? h - 12 : h;
  return `${displayHour}:${m}`;
}

/**
 * Genera las opciones de hora disponibles en el sistema, en pasos de
 * 5 minutos, en formato 24h ("HH:mm"). Por defecto cubre el rango
 * laboral estándar (06:00 a 13:00); se puede ajustar con parámetros
 * si en el futuro cambia el horario de atención.
 */
export function generateTimeOptions(
  startHour = 6,
  endHour = 13,
  stepMinutes = 5
): string[] {
  const opts: string[] = [];
  for (let h = startHour; h <= endHour; h++) {
    for (let m = 0; m < 60; m += stepMinutes) {
      if (h === endHour && m > 0) break;
      opts.push(
        `${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}`
      );
    }
  }
  return opts;
}

/**
 * Genera directamente las opciones de hora listas para <app-select>,
 * combinando generateTimeOptions() con su label en formato 12h.
 */
export function generateTimeSelectOptions(
  startHour = 6,
  endHour = 13,
  stepMinutes = 5
): { value: string; label: string }[] {
  return generateTimeOptions(startHour, endHour, stepMinutes).map((t) => ({
    value: t,
    label: formatTimeLabel12h(t),
  }));
}
