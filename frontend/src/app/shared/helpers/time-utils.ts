/**
 * Convierte una hora en formato 'HH:mm' a minutos totales desde
 * medianoche. Devuelve 0 si el valor es vacío/nulo.
 */
export function timeToMinutes(time: string): number {
  if (!time) return 0;
  const [h, m] = time.split(':').map(Number);
  return h * 60 + m;
}
