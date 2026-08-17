import {
  LucideActivity,
  LucideBone,
  LucideBuilding2,
  LucideHeart,
  LucideZap,
  type LucideIcon,
} from '@lucide/angular';

export interface SpecialtyMeta {
  value: string;
  label: string;
  icon: LucideIcon;
  color: string;
}

const SPECIALTY_CATALOG: Record<string, SpecialtyMeta> = {
  MEDICINA_GENERAL: {
    value: 'MEDICINA_GENERAL',
    label: 'Medicina General',
    icon: LucideHeart,
    color: 'text-red-700',
  },
  QUIROPRAXIA: {
    value: 'QUIROPRAXIA',
    label: 'Quiropraxia',
    icon: LucideBone,
    color: 'text-orange-700',
  },
  FISIOTERAPIA: {
    value: 'FISIOTERAPIA',
    label: 'Fisioterapia',
    icon: LucideActivity,
    color: 'text-green-700',
  },
  TERAPIA_NEURAL: {
    value: 'TERAPIA_NEURAL',
    label: 'Terapia Neural',
    icon: LucideZap,
    color: 'text-purple-700',
  },
};

const DEFAULT_SPECIALTY_META: Omit<SpecialtyMeta, 'value' | 'label'> = {
  icon: LucideBuilding2,
  color: 'text-gray-400',
};

/** Devuelve los metadatos (ícono, color, label) de una especialidad por su value. */
export function getSpecialtyMeta(value: string): SpecialtyMeta {
  return (
    SPECIALTY_CATALOG[value] ?? {
      value,
      label: value,
      ...DEFAULT_SPECIALTY_META,
    }
  );
}

/** Lista completa del catálogo de especialidades conocidas (para chips/selects fijos). */
export function getAllSpecialtiesMeta(): SpecialtyMeta[] {
  return Object.values(SPECIALTY_CATALOG);
}
