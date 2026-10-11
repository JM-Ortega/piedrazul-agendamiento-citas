/** Total de citas de un mes (month: 0 = enero … 11 = diciembre). */
export interface MonthlyTotalStat {
  month: number;
  total: number;
}

/** Un mes con un valor por serie (médico o especialidad). */
export interface MonthlySeriesStat {
  month: number;
  values: Record<string, number>;
}

/** Citas de un año desglosadas por serie (médicos o especialidades). */
export interface MonthlyBreakdown {
  series: string[];
  rows: MonthlySeriesStat[];
}

export interface MonthlyCancellationStat {
  month: number;
  /** Porcentaje 0-100. */
  rate: number;
}

/** Motivos por los que una cita no se realiza. */
export type MotivoInasistencia = Extract<
  EstadoCita,
  'CANCELADA' | 'NO_ASISTIO'
>;

export interface CancellationStats {
  months: { month: number; rate: number; count: number }[];
  yearRate: number;
  yearCount: number;
}

export type EstadoCita = 'AGENDADA' | 'ATENDIDA' | 'NO_ASISTIO' | 'CANCELADA';
/** Estados disponibles en el desplegable*/
export type EstadoFiltro = Exclude<EstadoCita, 'CANCELADA'>;

export interface DailyWorkloadStat {
  doctor: string;
  total: number;
}
