import {
  LucideActivity,
  LucideBone,
  LucideBrain,
  LucideEye,
  LucideHeart,
  LucideStethoscope,
  LucideZap,
  type LucideIcon,
} from '@lucide/angular';

export interface SpecialtyMeta {
  value: string;
  label: string;
  icon: LucideIcon;
  color: string;
}

/** Pool de íconos disponibles para asignar a cualquier especialidad, sin enumerar nombres. */
const ICON_POOL: LucideIcon[] = [
  LucideHeart,
  LucideBone,
  LucideActivity,
  LucideZap,
  LucideBrain,
  LucideEye,
  LucideStethoscope,
];

/** Pool de colores Tailwind con buen contraste, para variar visualmente los chips. */
const COLOR_POOL: string[] = [
  'text-red-700',
  'text-orange-700',
  'text-green-700',
  'text-purple-700',
  'text-blue-700',
  'text-pink-700',
  'text-teal-700',
];

/** Hash simple y estable (mismo string → mismo número siempre) para elegir ícono/color. */
function hashString(value: string): number {
  let hash = 0;
  for (let i = 0; i < value.length; i++) {
    hash = (hash * 31 + value.charCodeAt(i)) >>> 0;
  }
  return hash;
}

/** Convierte 'TERAPIA_NEURAL' -> 'Terapia Neural' sin depender de un catálogo de labels. */
function humanizeSpecialtyValue(value: string): string {
  return value
    .toLowerCase()
    .split('_')
    .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
    .join(' ');
}

/**
 * Devuelve los metadatos (ícono, color, label) de CUALQUIER especialidad,
 * generados de forma determinística a partir de su `value`. No requiere
 * mantener un catálogo enumerado: funciona igual si el backend agrega o
 * quita especialidades.
 */
export function getSpecialtyMeta(value: string): SpecialtyMeta {
  const hash = hashString(value);
  return {
    value,
    label: humanizeSpecialtyValue(value),
    icon: ICON_POOL[hash % ICON_POOL.length],
    color: COLOR_POOL[hash % COLOR_POOL.length],
  };
}

/** Mapea una lista de values de especialidad (del backend) a sus metadatos. */
export function getSpecialtiesMeta(values: string[]): SpecialtyMeta[] {
  return values.map(getSpecialtyMeta);
}
