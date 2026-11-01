export type Workday = 'LUNES' | 'MARTES' | 'MIERCOLES' | 'JUEVES' | 'VIERNES';

export const DAY_TO_WORKDAY: Record<number, Workday> = {
  1: 'LUNES',
  2: 'MARTES',
  3: 'MIERCOLES',
  4: 'JUEVES',
  5: 'VIERNES',
};

const WORKDAY_TO_DAY: Record<Workday, number> = {
  LUNES: 1,
  MARTES: 2,
  MIERCOLES: 3,
  JUEVES: 4,
  VIERNES: 5,
};

export function workdayToNumber(workday: Workday): number | null {
  return WORKDAY_TO_DAY[workday] ?? null;
}
