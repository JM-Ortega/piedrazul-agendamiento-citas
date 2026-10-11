import { NgTemplateOutlet } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  WritableSignal,
  computed,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import {
  LucideActivity,
  LucideStethoscope,
  LucideTrendingDown,
  LucideUsers,
  LucideCalendarDays,
} from '@lucide/angular';
import { ChartData, ChartOptions } from 'chart.js';
import {
  BaseChartDirective,
  provideCharts,
  withDefaultRegisterables,
} from 'ng2-charts';
import { Observable } from 'rxjs';
import { ButtonComponent } from '../../../../designSystem/atoms/button/button.component';
import {
  SelectComponent,
  SelectOption,
} from '../../../../designSystem/atoms/select/select.component';
import { TooltipDirective } from '../../../../designSystem/atoms/tooltip/tooltip.directive';
import {
  LegendItem,
  MONTH_NAMES,
  MonthPreset,
  PRIMARY,
  SERIES_COLORS,
  TOOLTIP_BASE,
  barOptions,
  hoverColumnPlugin,
  lineDrawAnimation,
  resolveMonths,
  thresholdPlugin,
  valueLabelsPlugin,
  averageLinePlugin,
} from '../../../../shared/helpers/statisticsCharts';
import {
  CancellationStats,
  EstadoFiltro,
  MonthlyBreakdown,
  MonthlySeriesStat,
  MonthlyTotalStat,
  DailyWorkloadStat,
  MotivoInasistencia,
} from '../../../../shared/models/dtos/statistics.dto';
import { StatisticsService } from '../../service/statistics.service';
import { formatLongDateEs } from '../../../../shared/helpers/dateFormat';
import { toIsoDateString } from '../../../../shared/helpers/transformDateLocal';
import { DatepickerComponent } from '../../../../designSystem/molecules/datepicker/datepicker.component';

/** Estados permitidos en el filtro de médico y especialidad. */
type EstadoAgendaFiltro = Extract<EstadoFiltro, 'AGENDADA' | 'ATENDIDA'>;

/** Colores por nivel de carga diaria. */
const WORKLOAD_COLORS = {
  normal: PRIMARY,
  high: '#f59e0b',
  overload: '#dc2626',
  empty: '#e2e8f0',
} as const;

/**
 * Vista de estadísticas del panel de administración: citas por mes, por
 * médico, por especialidad y tasa de cancelación. Los datos vienen de
 * `StatisticsService` (hoy simulados) y se grafican con Chart.js.
 *
 * Año y estado se editan como borrador y solo se consultan al pulsar
 * "Filtrar"; el rango de meses se aplica al instante sobre los datos en caché.
 */
@Component({
  selector: 'app-admin-statistics',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    NgTemplateOutlet,
    FormsModule,
    BaseChartDirective,
    ButtonComponent,
    SelectComponent,
    TooltipDirective,
    LucideActivity,
    LucideStethoscope,
    LucideUsers,
    LucideTrendingDown,
    LucideCalendarDays,
    DatepickerComponent,
  ],
  providers: [provideCharts(withDefaultRegisterables())],
  templateUrl: './adminStatistics.component.html',
})
export class AdminStatisticsComponent implements OnInit {
  private statisticsService = inject(StatisticsService);
  private destroyRef = inject(DestroyRef);

  readonly presetOptions: SelectOption[] = [
    { value: '3', label: 'Últ. 3 meses' },
    { value: '6', label: 'Últ. 6 meses' },
    { value: 'all', label: 'Todo el año' },
  ];

  /** Filtro de estado. La opción vacía de `app-select` equivale a "Todas". */
  readonly estadoOptions: SelectOption[] = [
    { value: 'AGENDADA', label: 'Agendada' },
    { value: 'ATENDIDA', label: 'Atendida' },
  ];

  /** Motivo de la tasa de no asistencia. */
  readonly motivoOptions: SelectOption[] = [
    { value: 'CANCELADA', label: 'Cancelada' },
    { value: 'NO_ASISTIO', label: 'No asistió' },
  ];

  // ── Carga de trabajo diaria: leyenda y opciones ───────────────────────────
  readonly workloadLegend: LegendItem[] = [
    { name: 'Normal', color: WORKLOAD_COLORS.normal },
    {
      name: 'Carga alta (>30% sobre el promedio)',
      color: WORKLOAD_COLORS.high,
    },
    {
      name: 'Sobrecarga (≥ al doble del promedio)',
      color: WORKLOAD_COLORS.overload,
    },
    { name: 'Sin citas', color: WORKLOAD_COLORS.empty },
  ];
  readonly workloadOptions = this.buildWorkloadOptions();

  // ── Opciones y plugins de cada gráfica (constantes) ───────────────────────
  readonly monthlyOptions = barOptions(false);
  readonly monthlyPlugins = [hoverColumnPlugin, valueLabelsPlugin];
  readonly groupedOptions = barOptions(true);
  readonly groupedPlugins = [hoverColumnPlugin];

  readonly cancelOptions: ChartOptions<'line'> = {
    responsive: true,
    maintainAspectRatio: false,
    layout: { padding: { top: 12, right: 12 } },
    interaction: { mode: 'nearest', intersect: false },
    animation: lineDrawAnimation(),
    plugins: {
      legend: { display: false },
      tooltip: {
        ...TOOLTIP_BASE,
        borderColor: '#fecaca',
        titleColor: '#7f1d1d',
        displayColors: false,
        callbacks: {
          label: (item) => {
            const count = this.cancelData().months[item.dataIndex]?.count ?? 0;
            const citaTexto = count === 1 ? 'cita' : 'citas';
            return `${item.parsed.y}% = ${count} ${citaTexto}`;
          },
          afterLabel: () => {
            return this.cancelMotivo() === 'CANCELADA'
              ? 'tasa de cancelación'
              : 'tasa de inasistencia';
          },
        },
      },
    },
    scales: {
      x: {
        grid: { display: false },
        border: { display: false },
        ticks: { color: '#991b1b', font: { size: 14, weight: 'bold' } },
      },
      y: {
        min: 0,
        max: 100,
        grid: { color: '#fee2e2' },
        border: { display: false, dash: [4, 4] },
        ticks: {
          stepSize: 20,
          color: '#94a3b8',
          font: { size: 12 },
          callback: (v) => `${v}%`,
        },
      },
    },
  };
  readonly cancelPlugins = [thresholdPlugin];

  years = signal<number[]>([]);
  errorMessage = signal('');

  /** Años en el formato que espera `app-select` (value/label como string). */
  readonly yearOptions = computed<SelectOption[]>(() =>
    this.years().map((y) => ({ value: String(y), label: String(y) }))
  );

  // ── Carga de trabajo diaria ───────────────────────────────────────────────
  // `workloadDate` es la fecha aplicada; `workloadDateDraft` es la del input.
  workloadDate = signal(toIsoDateString(new Date()));
  workloadDateDraft = signal<string | null>(this.workloadDate());
  private workloadData = signal<DailyWorkloadStat[]>([]);
  readonly workloadDirty = computed(() => {
    const draft = this.workloadDateDraft();
    return draft !== null && draft !== this.workloadDate();
  });
  readonly workloadDateLabel = computed(() =>
    formatLongDateEs(this.workloadDate())
  );
  readonly workloadTotal = computed(() =>
    this.workloadData().reduce((sum, d) => sum + d.total, 0)
  );
  /** Promedio entre médicos con citas; 0 si hay menos de dos (no es comparable). */
  readonly workloadAvg = computed(() => {
    const withAppts = this.workloadData().filter((d) => d.total > 0);
    if (withAppts.length < 2) return 0;
    const sum = withAppts.reduce((s, d) => s + d.total, 0);
    return Math.round((sum / withAppts.length) * 10) / 10;
  });

  // ── Citas por mes (solo atendidas) ────────────────────────────────────────
  // `monthlyYear` es el año aplicado; `monthlyYearDraft` es lo que muestra el select.
  monthlyYear = signal(new Date().getFullYear());
  monthlyYearDraft = signal(new Date().getFullYear());
  monthlyPreset = signal<MonthPreset>('6');
  private monthlyData = signal<MonthlyTotalStat[]>([]);
  readonly monthlyDirty = computed(
    () => this.monthlyYearDraft() !== this.monthlyYear()
  );

  // ── Citas por médico ──────────────────────────────────────────────────────
  doctorYear = signal(new Date().getFullYear());
  doctorYearDraft = signal(new Date().getFullYear());
  doctorEstado = signal<EstadoAgendaFiltro | ''>('');
  doctorEstadoDraft = signal<EstadoAgendaFiltro | ''>('');
  doctorPreset = signal<MonthPreset>('6');
  private doctorData = signal<MonthlyBreakdown>({ series: [], rows: [] });
  readonly doctorDirty = computed(
    () =>
      this.doctorYearDraft() !== this.doctorYear() ||
      this.doctorEstadoDraft() !== this.doctorEstado()
  );
  readonly doctorSubtitle = computed(() =>
    this.breakdownSubtitle(this.doctorEstado())
  );

  // ── Comparativo por especialidad ──────────────────────────────────────────
  specialtyYear = signal(new Date().getFullYear());
  specialtyYearDraft = signal(new Date().getFullYear());
  specialtyEstado = signal<EstadoAgendaFiltro | ''>('');
  specialtyEstadoDraft = signal<EstadoAgendaFiltro | ''>('');
  specialtyPreset = signal<MonthPreset>('6');
  private specialtyData = signal<MonthlyBreakdown>({ series: [], rows: [] });
  readonly specialtyDirty = computed(
    () =>
      this.specialtyYearDraft() !== this.specialtyYear() ||
      this.specialtyEstadoDraft() !== this.specialtyEstado()
  );
  readonly specialtySubtitle = computed(() =>
    this.breakdownSubtitle(this.specialtyEstado())
  );

  // ── Tasa de no asistencia ─────────────────────────────────────────────────
  cancelYear = signal(new Date().getFullYear());
  cancelMotivo = signal<MotivoInasistencia>('CANCELADA');
  private cancelData = signal<CancellationStats>({
    months: [],
    yearRate: 0,
    yearCount: 0,
  });

  readonly cancelYearRate = computed(() => this.cancelData().yearRate);
  readonly cancelYearCount = computed(() => this.cancelData().yearCount);

  // ── Datos de las gráficas (computed) ──────────────────────────────────────
  readonly workloadChart = computed<ChartData<'bar'>>(() => {
    const data = this.workloadData();
    const avg = this.workloadAvg();
    return {
      labels: data.map((d) => d.doctor),
      datasets: [
        {
          label: 'Citas',
          data: data.map((d) => d.total),
          backgroundColor: data.map((d) => this.workloadColor(d.total, avg)),
          borderRadius: { topLeft: 8, topRight: 8 },
          borderSkipped: false,
          maxBarThickness: 64,
        },
      ],
    };
  });

  readonly workloadPlugins = computed(() => [
    hoverColumnPlugin,
    valueLabelsPlugin,
    averageLinePlugin(this.workloadAvg()),
  ]);

  readonly monthlyChart = computed<ChartData<'bar'>>(() => {
    const rows = this.filterMonths(
      this.monthlyData(),
      this.monthlyYear(),
      this.monthlyPreset()
    );
    return {
      labels: rows.map((r) => MONTH_NAMES[r.month]),
      datasets: [
        {
          label: 'Citas',
          data: rows.map((r) => r.total),
          backgroundColor: PRIMARY,
          borderRadius: { topLeft: 8, topRight: 8 },
          borderSkipped: false,
          maxBarThickness: 64,
        },
      ],
    };
  });

  readonly doctorChart = computed(() =>
    this.buildBreakdownChart(
      this.doctorData(),
      this.doctorYear(),
      this.doctorPreset()
    )
  );

  readonly specialtyChart = computed(() =>
    this.buildBreakdownChart(
      this.specialtyData(),
      this.specialtyYear(),
      this.specialtyPreset()
    )
  );

  readonly cancelChart = computed<ChartData<'line'>>(() => {
    const months = this.cancelData().months;
    return {
      labels: months.map((m) => MONTH_NAMES[m.month]),
      datasets: [
        {
          label: 'Tasa cancelación',
          data: months.map((m) => m.rate),
          borderColor: '#dc2626',
          borderWidth: 3,
          tension: 0,
          pointRadius: 5,
          pointBackgroundColor: '#dc2626',
          pointBorderColor: '#ffffff',
          pointBorderWidth: 2,
          pointHoverRadius: 8,
          pointHoverBackgroundColor: '#dc2626',
          pointHoverBorderColor: '#ffffff',
          pointHoverBorderWidth: 2,
        },
      ],
    };
  });

  // ── Lifecycle ─────────────────────────────────────────────────────────────
  ngOnInit(): void {
    this.statisticsService
      .getAvailableYears()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (years) => {
          this.years.set(years);
          const year = years[0] ?? new Date().getFullYear();
          this.monthlyYear.set(year);
          this.monthlyYearDraft.set(year);
          this.doctorYear.set(year);
          this.doctorYearDraft.set(year);
          this.specialtyYear.set(year);
          this.specialtyYearDraft.set(year);
          this.cancelYear.set(year);
          this.refresh();
        },
        error: () =>
          this.errorMessage.set('No se pudieron cargar las estadísticas.'),
      });
  }

  // ── Refresco (siempre con los filtros aplicados, no con los borradores) ───
  /** Botón global "Refrescar": vuelve a consultar todas las secciones. */
  refresh(): void {
    this.refreshWorkload();
    this.refreshMonthly();
    this.refreshDoctor();
    this.refreshSpecialty();
    this.refreshCancel();
  }

  private refreshWorkload(): void {
    this.load(
      this.statisticsService.getDailyWorkload(this.workloadDate()),
      this.workloadData
    );
  }

  /** Handler de borrador: se actualiza `workloadDateDraft` al cambiar la fecha en el input.
   * `null` = fecha incompleta o inválida: deshabilita "Filtrar".
   */
  onWorkloadDateChange(date: Date | null): void {
    this.workloadDateDraft.set(date ? toIsoDateString(date) : null);
  }

  // Botón "Filtrar"
  applyWorkloadFilters(): void {
    const draft = this.workloadDateDraft();
    if (!draft) return;
    this.workloadDate.set(draft);
    this.refreshWorkload();
  }

  private refreshMonthly(): void {
    this.load(
      this.statisticsService.getMonthlyTotals(this.monthlyYear(), 'ATENDIDA'),
      this.monthlyData
    );
  }

  private refreshDoctor(): void {
    this.load(
      this.statisticsService.getMonthlyByDoctor(
        this.doctorYear(),
        this.doctorEstado() || undefined
      ),
      this.doctorData
    );
  }

  private refreshSpecialty(): void {
    this.load(
      this.statisticsService.getMonthlyBySpecialty(
        this.specialtyYear(),
        this.specialtyEstado() || undefined
      ),
      this.specialtyData
    );
  }

  private refreshCancel(): void {
    this.load(
      this.statisticsService.getCancellationRate(
        this.cancelYear(),
        this.cancelMotivo()
      ),
      this.cancelData
    );
  }

  onCancelMotivoChange(value: string): void {
    if (!value) return;
    this.cancelMotivo.set(value as MotivoInasistencia);
    this.refreshCancel();
  }

  // ── Handlers de borrador (no consultan) ───────────────────────────────────
  // `app-select` siempre incluye una opción vacía ("Seleccione..."): en año se
  // ignora; en estado equivale a "Todas".
  onMonthlyYearChange(value: string): void {
    if (!value) return;
    this.monthlyYearDraft.set(Number(value));
  }

  onDoctorYearChange(value: string): void {
    if (!value) return;
    this.doctorYearDraft.set(Number(value));
  }

  onDoctorEstadoChange(value: string): void {
    this.doctorEstadoDraft.set(value as EstadoAgendaFiltro | '');
  }

  onSpecialtyYearChange(value: string): void {
    if (!value) return;
    this.specialtyYearDraft.set(Number(value));
  }

  onSpecialtyEstadoChange(value: string): void {
    this.specialtyEstadoDraft.set(value as EstadoAgendaFiltro | '');
  }

  // ── Botones "Filtrar": aplican el borrador y consultan ────────────────────
  applyMonthlyFilters(): void {
    this.monthlyYear.set(this.monthlyYearDraft());
    this.refreshMonthly();
  }

  applyDoctorFilters(): void {
    this.doctorYear.set(this.doctorYearDraft());
    this.doctorEstado.set(this.doctorEstadoDraft());
    this.refreshDoctor();
  }

  applySpecialtyFilters(): void {
    this.specialtyYear.set(this.specialtyYearDraft());
    this.specialtyEstado.set(this.specialtyEstadoDraft());
    this.refreshSpecialty();
  }

  // ── Handlers que se aplican al instante ───────────────────────────────────
  // El rango solo filtra en el cliente sobre los datos en caché.
  onMonthlyPresetChange(value: string): void {
    if (!value) return;
    this.monthlyPreset.set(value as MonthPreset);
  }

  onDoctorPresetChange(value: string): void {
    if (!value) return;
    this.doctorPreset.set(value as MonthPreset);
  }

  onSpecialtyPresetChange(value: string): void {
    if (!value) return;
    this.specialtyPreset.set(value as MonthPreset);
  }

  onCancelYearChange(value: string): void {
    if (!value) return;
    this.cancelYear.set(Number(value));
    this.refreshCancel();
  }

  // ── Private ───────────────────────────────────────────────────────────────
  private load<T>(source$: Observable<T>, target: WritableSignal<T>): void {
    this.errorMessage.set('');
    source$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (value) => target.set(value),
      error: () =>
        this.errorMessage.set('No se pudieron cargar las estadísticas.'),
    });
  }

  private breakdownSubtitle(estado: EstadoAgendaFiltro | ''): string {
    switch (estado) {
      case 'ATENDIDA':
        return 'Mes a mes · citas atendidas';
      case 'AGENDADA':
        return 'Mes a mes · citas agendadas';
      default:
        return 'Mes a mes · citas agendadas y atendidas';
    }
  }

  private filterMonths<T extends { month: number }>(
    rows: T[],
    year: number,
    preset: MonthPreset
  ): T[] {
    const allowed = resolveMonths(year, preset);
    return rows.filter((r) => allowed.includes(r.month));
  }

  private buildBreakdownChart(
    data: MonthlyBreakdown,
    year: number,
    preset: MonthPreset
  ): { chart: ChartData<'bar'>; legend: LegendItem[] } {
    const rows: MonthlySeriesStat[] = this.filterMonths(
      data.rows,
      year,
      preset
    );
    const all: LegendItem[] = data.series.map((name, i) => ({
      name,
      color: SERIES_COLORS[i % SERIES_COLORS.length],
    }));
    // Solo se muestran las series con al menos una cita en el rango elegido.
    const legend = all.filter((s) =>
      rows.some((r) => (r.values[s.name] ?? 0) > 0)
    );

    return {
      legend,
      chart: {
        labels: rows.map((r) => MONTH_NAMES[r.month]),
        datasets: legend.map((s) => ({
          label: s.name,
          data: rows.map((r) => r.values[s.name] ?? 0),
          backgroundColor: s.color,
          borderRadius: { topLeft: 6, topRight: 6 },
          borderSkipped: false,
          maxBarThickness: 36,
        })),
      },
    };
  }

  /** Normal, carga alta (>30% sobre el promedio), sobrecarga (≥ 2×) o sin citas. */
  private workloadColor(total: number, avg: number): string {
    if (total === 0) return WORKLOAD_COLORS.empty;
    if (avg === 0) return WORKLOAD_COLORS.normal;
    if (total >= avg * 2) return WORKLOAD_COLORS.overload;
    if (total > avg * 1.3) return WORKLOAD_COLORS.high;
    return WORKLOAD_COLORS.normal;
  }

  /** Igual que el gráfico mensual, pero el tooltip muestra "N citas". */
  private buildWorkloadOptions(): ChartOptions<'bar'> {
    const base = barOptions(false);
    return {
      ...base,
      plugins: {
        ...base.plugins,
        tooltip: {
          ...base.plugins?.tooltip,
          displayColors: false,
          callbacks: {
            label: (item) =>
              `${item.parsed.y} ${item.parsed.y === 1 ? 'cita' : 'citas'}`,
          },
        },
      },
    };
  }
}
