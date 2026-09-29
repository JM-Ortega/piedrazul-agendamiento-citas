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
import {
  LucideActivity,
  LucideStethoscope,
  LucideTrendingDown,
  LucideUsers,
} from '@lucide/angular';
import { ChartData, ChartOptions } from 'chart.js';
import {
  BaseChartDirective,
  provideCharts,
  withDefaultRegisterables,
} from 'ng2-charts';
import { Observable } from 'rxjs';
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
} from '../../../../shared/helpers/statisticsCharts';
import {
  CancellationStats,
  MonthlyBreakdown,
  MonthlySeriesStat,
  MonthlyTotalStat,
} from '../../../../shared/models/dtos/statistics.dto';
import { StatisticsService } from '../../service/statistics.service';

/**
 * Vista de estadísticas del panel de administración: citas por mes, por
 * médico, por especialidad y tasa de cancelación. Los datos vienen de
 * `StatisticsService` (hoy simulados) y se grafican con Chart.js.
 */
@Component({
  selector: 'app-admin-statistics',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    NgTemplateOutlet,
    BaseChartDirective,
    LucideActivity,
    LucideStethoscope,
    LucideUsers,
    LucideTrendingDown,
  ],
  providers: [provideCharts(withDefaultRegisterables())],
  templateUrl: './adminStatistics.component.html',
})
export class AdminStatisticsComponent implements OnInit {
  private statisticsService = inject(StatisticsService);
  private destroyRef = inject(DestroyRef);

  readonly presetOptions: { value: MonthPreset; label: string }[] = [
    { value: '3', label: 'Últ. 3 meses' },
    { value: '6', label: 'Últ. 6 meses' },
    { value: 'all', label: 'Todo el año' },
  ];

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
          label: (item) => `${item.parsed.y}%`,
          afterLabel: () => 'tasa de cancelación',
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

  // ── Citas por mes ─────────────────────────────────────────────────────────
  monthlyYear = signal(new Date().getFullYear());
  monthlyPreset = signal<MonthPreset>('6');
  private monthlyData = signal<MonthlyTotalStat[]>([]);

  // ── Citas por médico ──────────────────────────────────────────────────────
  doctorYear = signal(new Date().getFullYear());
  doctorPreset = signal<MonthPreset>('6');
  private doctorData = signal<MonthlyBreakdown>({ series: [], rows: [] });

  // ── Comparativo por especialidad ──────────────────────────────────────────
  specialtyYear = signal(new Date().getFullYear());
  specialtyPreset = signal<MonthPreset>('6');
  private specialtyData = signal<MonthlyBreakdown>({ series: [], rows: [] });

  // ── Tasa de cancelación ───────────────────────────────────────────────────
  cancelYear = signal(new Date().getFullYear());
  private cancelData = signal<CancellationStats>({ months: [], yearRate: 0 });

  readonly cancelYearRate = computed(() => this.cancelData().yearRate);

  // ── Datos de las gráficas (computed) ──────────────────────────────────────
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
          this.doctorYear.set(year);
          this.specialtyYear.set(year);
          this.cancelYear.set(year);
          this.load(
            this.statisticsService.getMonthlyTotals(year),
            this.monthlyData
          );
          this.load(
            this.statisticsService.getMonthlyByDoctor(year),
            this.doctorData
          );
          this.load(
            this.statisticsService.getMonthlyBySpecialty(year),
            this.specialtyData
          );
          this.load(
            this.statisticsService.getCancellationRate(year),
            this.cancelData
          );
        },
        error: () =>
          this.errorMessage.set('No se pudieron cargar las estadísticas.'),
      });
  }

  // ── Handlers de año ───────────────────────────────────────────────────────
  onMonthlyYearChange(value: string): void {
    const year = Number(value);
    this.monthlyYear.set(year);
    this.load(this.statisticsService.getMonthlyTotals(year), this.monthlyData);
  }

  onDoctorYearChange(value: string): void {
    const year = Number(value);
    this.doctorYear.set(year);
    this.load(this.statisticsService.getMonthlyByDoctor(year), this.doctorData);
  }

  onSpecialtyYearChange(value: string): void {
    const year = Number(value);
    this.specialtyYear.set(year);
    this.load(
      this.statisticsService.getMonthlyBySpecialty(year),
      this.specialtyData
    );
  }

  onCancelYearChange(value: string): void {
    const year = Number(value);
    this.cancelYear.set(year);
    this.load(
      this.statisticsService.getCancellationRate(year),
      this.cancelData
    );
  }

  // ── Helpers de template ───────────────────────────────────────────────────
  presetClass(active: boolean): string {
    return (
      'px-3 py-1.5 rounded-lg text-xs font-semibold transition-colors cursor-pointer ' +
      (active
        ? 'bg-[#215c98] text-white shadow-sm'
        : 'bg-blue-50 text-[#215c98] hover:bg-[#d9e9f8] border border-[#a7c9ec]')
    );
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
}
