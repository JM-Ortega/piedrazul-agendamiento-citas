import { ChartOptions, Plugin } from 'chart.js';

export type MonthPreset = '3' | '6' | 'all';

export interface LegendItem {
  name: string;
  color: string;
}

export const MONTH_NAMES = [
  'Ene',
  'Feb',
  'Mar',
  'Abr',
  'May',
  'Jun',
  'Jul',
  'Ago',
  'Sep',
  'Oct',
  'Nov',
  'Dic',
];
export const SERIES_COLORS = [
  '#215c98',
  '#4e92d9',
  '#1f7a52',
  '#d97706',
  '#7c3aed',
  '#163c63',
];
export const PRIMARY = '#215c98';
export const ANIMATION_MS = 1000;

/** Meses (0-11) a mostrar según el preset elegido, terminando en el mes actual si es el año en curso. */
export function resolveMonths(year: number, preset: MonthPreset): number[] {
  if (preset === 'all') return Array.from({ length: 12 }, (_, i) => i);
  const now = new Date();
  const lastMonth = year === now.getFullYear() ? now.getMonth() : 11;
  const count = preset === '3' ? 3 : 6;
  const months: number[] = [];
  for (let i = count - 1; i >= 0; i--) {
    const m = lastMonth - i;
    if (m >= 0) months.push(m);
  }
  return months;
}

// ── Plugins de Chart.js ─────────────────────────────────────────────────────
/** Resalta la columna bajo el cursor (equivale al `cursor` de Recharts). */
export const hoverColumnPlugin: Plugin<'bar'> = {
  id: 'hoverColumn',
  beforeDatasetsDraw(chart) {
    const active = chart.tooltip?.getActiveElements();
    if (!active?.length) return;
    const { ctx, chartArea } = chart;
    const count = chart.data.labels?.length ?? 1;
    const width = chartArea.width / count;
    ctx.save();
    ctx.fillStyle = '#eff6ff';
    ctx.fillRect(
      active[0].element.x - width / 2,
      chartArea.top,
      width,
      chartArea.height
    );
    ctx.restore();
  },
};

/** Dibuja el valor encima de cada barra (solo para la gráfica "Citas por Mes"). */
export const valueLabelsPlugin: Plugin<'bar'> = {
  id: 'valueLabels',
  afterDatasetsDraw(chart) {
    const { ctx } = chart;
    ctx.save();
    ctx.font = '800 16px sans-serif';
    ctx.fillStyle = '#163c63';
    ctx.textAlign = 'center';
    chart.getDatasetMeta(0).data.forEach((bar, i) => {
      const value = chart.data.datasets[0].data[i];
      ctx.fillText(String(value), bar.x, bar.y - 6);
    });
    ctx.restore();
  },
};

/** Línea punteada del umbral de alerta (20%) en la gráfica de cancelación. */
export const thresholdPlugin: Plugin<'line'> = {
  id: 'threshold',
  beforeDatasetsDraw(chart) {
    const { ctx, chartArea, scales } = chart;
    const y = scales['y'].getPixelForValue(20);
    ctx.save();
    ctx.setLineDash([6, 3]);
    ctx.strokeStyle = '#fca5a5';
    ctx.lineWidth = 1.5;
    ctx.beginPath();
    ctx.moveTo(chartArea.left, y);
    ctx.lineTo(chartArea.right, y);
    ctx.stroke();
    ctx.restore();
  },
};

// ── Opciones ────────────────────────────────────────────────────────────────
export const TOOLTIP_BASE = {
  backgroundColor: '#ffffff',
  borderColor: '#a7c9ec',
  borderWidth: 2,
  titleColor: '#163c63',
  bodyColor: '#374151',
  titleFont: { size: 15, weight: 'bold' as const },
  bodyFont: { size: 13, weight: 600 as const },
  padding: 12,
  cornerRadius: 12,
  boxPadding: 4,
};

export function barOptions(showYAxis: boolean): ChartOptions<'bar'> {
  return {
    responsive: true,
    maintainAspectRatio: false,
    layout: { padding: { top: 24 } },
    interaction: { mode: 'index', intersect: false },
    animation: { duration: ANIMATION_MS, easing: 'easeOutQuart' },
    plugins: {
      legend: { display: false },
      tooltip: {
        ...TOOLTIP_BASE,
        // Solo series con valor > 0 (igual que el tooltip de la referencia).
        filter: (item) => (item.parsed.y ?? 0) > 0,
      },
    },
    scales: {
      x: {
        grid: { display: false },
        border: { display: false },
        ticks: { color: '#163c63', font: { size: 14, weight: 'bold' } },
      },
      y: {
        display: showYAxis,
        beginAtZero: true,
        grace: showYAxis ? 0 : '15%',
        grid: { color: '#d9e9f8' },
        border: { display: false, dash: [4, 4] },
        ticks: { color: '#94a3b8', font: { size: 12 }, precision: 0 },
      },
    },
  };
}

/**
 * Animación de la línea: se dibuja de izquierda a derecha, punto por punto
 * (patrón "progressive line" de Chart.js).
 */
export function lineDrawAnimation() {
  const totalDuration = ANIMATION_MS * 1.5;
  const delayBetweenPoints = totalDuration / 12;
  /* eslint-disable @typescript-eslint/no-explicit-any */
  const previousY = (ctx: any): number =>
    ctx.index === 0
      ? ctx.chart.scales['y'].getPixelForValue(0)
      : ctx.chart
          .getDatasetMeta(ctx.datasetIndex)
          .data[ctx.index - 1].getProps(['y'], true).y;
  const delay = (ctx: any): number =>
    ctx.type !== 'data' || ctx.xStarted
      ? 0
      : ((ctx.xStarted = true), ctx.index * delayBetweenPoints);
  return {
    x: {
      type: 'number',
      easing: 'linear',
      duration: delayBetweenPoints,
      from: NaN,
      delay,
    },
    y: {
      type: 'number',
      easing: 'linear',
      duration: delayBetweenPoints,
      from: previousY,
      delay: (ctx: any): number =>
        ctx.type !== 'data' || ctx.yStarted
          ? 0
          : ((ctx.yStarted = true), ctx.index * delayBetweenPoints),
    },
  } as any;
  /* eslint-enable @typescript-eslint/no-explicit-any */
}

/** Línea punteada horizontal con el promedio. No dibuja nada si `avg` es 0. */
export const averageLinePlugin = (avg: number): Plugin<'bar'> => ({
  id: 'averageLine',
  afterDatasetsDraw(chart) {
    const scale = chart.scales['y'];
    if (avg <= 0 || !scale) return;
    const { ctx, chartArea } = chart;
    const y = scale.getPixelForValue(avg);
    ctx.save();
    ctx.strokeStyle = '#f59e0b';
    ctx.lineWidth = 2;
    ctx.setLineDash([6, 3]);
    ctx.beginPath();
    ctx.moveTo(chartArea.left, y);
    ctx.lineTo(chartArea.right, y);
    ctx.stroke();
    ctx.setLineDash([]);
    ctx.fillStyle = '#b45309';
    ctx.font = 'bold 12px sans-serif';
    ctx.textAlign = 'right';
    ctx.textBaseline = 'bottom';
    ctx.fillText(`Promedio: ${avg}`, chartArea.right, y - 4);
    ctx.restore();
  },
});
