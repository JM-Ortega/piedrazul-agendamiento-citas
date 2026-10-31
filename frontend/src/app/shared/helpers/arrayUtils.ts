/**
 * Alterna un elemento dentro de un arreglo: si ya está presente, lo quita;
 * si no está, lo agrega al final. Devuelve un arreglo nuevo (no muta el original).
 */
export function toggleInArray<T>(array: T[], item: T): T[] {
  return array.includes(item)
    ? array.filter((x) => x !== item)
    : [...array, item];
}
