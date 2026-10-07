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
} from '../../../../shared/helpers/statisticsCharts';
import {
  CancellationStats,
  EstadoFiltro,
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
    FormsModule,
    BaseChartDirective,
    ButtonComponent,
    SelectComponent,
    TooltipDirective,
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

  /** Filtro de estado. La opción vacía de `app-select` equivale a "Todas". */
  readonly estadoOptions: SelectOption[] = [
    { value: 'AGENDADA', label: 'Agendada' },
    { value: 'ATENDIDA', label: 'Atendida' },
    { value: 'NO_ASISTIO', label: 'No asistió' },
  ];

  // ── Estilos de los chips de rango (app-button variant="chip") ─────────────
  readonly presetExtraClass = '!w-auto !py-1.5 !px-3 !text-xs !rounded-lg';
  readonly presetActiveClasses =
    'border-[#215c98] bg-[#215c98] text-white shadow-sm';
  readonly presetInactiveClasses =
    'border-[#a7c9ec] bg-blue-50 text-[#215c98] hover:bg-[#d9e9f8]';

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

  /** Años en el formato que espera `app-select` (value/label como string). */
  readonly yearOptions = computed<SelectOption[]>(() =>
    this.years().map((y) => ({ value: String(y), label: String(y) }))
  );

  // ── Citas por mes ─────────────────────────────────────────────────────────
  monthlyYear = signal(new Date().getFullYear());
  monthlyPreset = signal<MonthPreset>('6');
  monthlyEstado = signal<EstadoFiltro | ''>('');
  private monthlyData = signal<MonthlyTotalStat[]>([]);

  // ── Citas por médico ──────────────────────────────────────────────────────
  doctorYear = signal(new Date().getFullYear());
  doctorPreset = signal<MonthPreset>('6');
  doctorEstado = signal<EstadoFiltro | ''>('');
  private doctorData = signal<MonthlyBreakdown>({ series: [], rows: [] });

  // ── Comparativo por especialidad ──────────────────────────────────────────
  specialtyYear = signal(new Date().getFullYear());
  specialtyPreset = signal<MonthPreset>('6');
  specialtyEstado = signal<EstadoFiltro | ''>('');
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
          this.refreshMonthly();
          this.refreshDoctor();
          this.refreshSpecialty();
          this.refreshCancel();
        },
        error: () =>
          this.errorMessage.set('No se pudieron cargar las estadísticas.'),
      });
  }

  // ── Refresco (también lo usa el botón "Refrescar") ────────────────────────
  refreshMonthly(): void {
    this.load(
      this.statisticsService.getMonthlyTotals(
        this.monthlyYear(),
        this.monthlyEstado() || undefined
      ),
      this.monthlyData
    );
  }

  refreshDoctor(): void {
    this.load(
      this.statisticsService.getMonthlyByDoctor(
        this.doctorYear(),
        this.doctorEstado() || undefined
      ),
      this.doctorData
    );
  }

  refreshSpecialty(): void {
    this.load(
      this.statisticsService.getMonthlyBySpecialty(
        this.specialtyYear(),
        this.specialtyEstado() || undefined
      ),
      this.specialtyData
    );
  }

  refreshCancel(): void {
    this.load(
      this.statisticsService.getCancellationRate(this.cancelYear()),
      this.cancelData
    );
  }

  // ── Handlers ──────────────────────────────────────────────────────────────
  // `app-select` siempre incluye una opción vacía ("Seleccione..."): en año se
  // ignora; en estado equivale a "Todas".
  onMonthlyYearChange(value: string): void {
    if (!value) return;
    this.monthlyYear.set(Number(value));
    this.refreshMonthly();
  }

  onDoctorYearChange(value: string): void {
    if (!value) return;
    this.doctorYear.set(Number(value));
    this.refreshDoctor();
  }

  onSpecialtyYearChange(value: string): void {
    if (!value) return;
    this.specialtyYear.set(Number(value));
    this.refreshSpecialty();
  }

  onCancelYearChange(value: string): void {
    if (!value) return;
    this.cancelYear.set(Number(value));
    this.refreshCancel();
  }

  onMonthlyEstadoChange(value: string): void {
    this.monthlyEstado.set(value as EstadoFiltro | '');
    this.refreshMonthly();
  }

  onDoctorEstadoChange(value: string): void {
    this.doctorEstado.set(value as EstadoFiltro | '');
    this.refreshDoctor();
  }

  onSpecialtyEstadoChange(value: string): void {
    this.specialtyEstado.set(value as EstadoFiltro | '');
    this.refreshSpecialty();
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
