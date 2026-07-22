import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import {
  LucideCalendar,
  LucideCircleCheck,
  LucideCircleUser,
  LucideClock,
  LucideCreditCard,
  LucideDownload,
  LucideDynamicIcon,
  LucideFileSpreadsheet,
  LucideFileText,
  LucidePhone,
  LucideStethoscope,
  LucideTag,
  type LucideIcon,
} from '@lucide/angular';
import { SchedulerService } from '../../../core/services/scheduler.service';
import { AppointmentExportRequest } from '../../../shared/models/dtos/AppointmentExportRequest.dto';
import { ExportColumnBackend } from '../../../shared/models/types/ExportColumnBackend.type';
import { ExportFormatBackend } from '../../../shared/models/types/ExportFormatBackend.type';
import { ButtonComponent } from '../../atoms/button.component';

// ── Tipos ─────────────────────────────────────────────────────────────────────
export type ExportColumnKey =
  | 'date'
  | 'time'
  | 'patient'
  | 'documentId'
  | 'phone'
  | 'status'
  | 'specialty'
  | 'doctorName';

type ExportFormat = 'excel' | 'pdf' | 'csv';
type ExportColumns = Record<ExportColumnKey, boolean>;

interface ColumnDef {
  key: ExportColumnKey;
  label: string;
  icon: LucideIcon;
}

interface FormatDef {
  value: ExportFormat;
  label: string;
  active: string;
  icon: LucideIcon;
  activeIcon: string;
  activeLabel: string;
}

const COLUMN_MAP: Record<ExportColumnKey, ExportColumnBackend> = {
  date: 'FECHA_CITA',
  time: 'HORA_CITA',
  patient: 'NOMBRE_PACIENTE',
  documentId: 'DOCUMENTO_IDENTIDAD',
  phone: 'TELEFONO_PACIENTE',
  status: 'ESTADO_CITA',
  specialty: 'ESPECIALIDAD',
  doctorName: 'NOMBRE_MEDICO',
};

const FORMAT_MAP: Record<ExportFormat, ExportFormatBackend> = {
  excel: 'EXCEL',
  pdf: 'PDF',
  csv: 'CSV',
};

const MIME_MAP: Record<ExportFormat, string> = {
  excel: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  pdf: 'application/pdf',
  csv: 'text/csv;charset=utf-8;',
};

const EXT_MAP: Record<ExportFormat, string> = {
  excel: 'xlsx',
  pdf: 'pdf',
  csv: 'csv',
};

@Component({
  selector: 'app-export-modal',
  templateUrl: './export-modal.component.html',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [LucideDynamicIcon, LucideDownload, LucideCalendar, ButtonComponent],
})
export class ExportModalComponent {
  private schedulerService = inject(SchedulerService);

  // ── Inputs desde el padre ──────────────────────────────────────────────────

  /** ID del doctor. Si es null → no filtra por doctor (agendador sin filtro) */
  idDoctor = input<string | null>(null);

  /** Estado activo. Si es null o vacío → todos los estados */
  filterStatus = input<string | null>(null);

  /** Nombre legible del doctor filtrado (para mostrar en el aviso) */
  doctorName = input<string | null>(null);

  /** Fecha de hoy formateada para mostrar en el aviso */
  todayFormatted = input.required<string>();

  // ── Output ─────────────────────────────────────────────────────────────────
  /** Emite cuando el modal debe cerrarse (éxito o cancelación) */
  closed = output<void>();

  // ── Estado interno ─────────────────────────────────────────────────────────
  exportFormat = signal<ExportFormat>('excel');
  exportingInProgress = signal(false);
  exportError = signal<string | null>(null);

  exportColumns = signal<ExportColumns>({
    date: true,
    time: true,
    patient: true,
    documentId: true,
    phone: true,
    status: true,
    specialty: true,
    doctorName: true,
  });

  readonly columnDefs: ColumnDef[] = [
    { key: 'date', label: 'Fecha de la Cita', icon: LucideCalendar },
    { key: 'time', label: 'Hora de la Cita', icon: LucideClock },
    { key: 'patient', label: 'Nombre del Paciente', icon: LucideCircleUser },
    {
      key: 'documentId',
      label: 'Documento de Identidad',
      icon: LucideCreditCard,
    },
    { key: 'phone', label: 'Teléfono del Paciente', icon: LucidePhone },
    { key: 'status', label: 'Estado de la Cita', icon: LucideCircleCheck },
    { key: 'specialty', label: 'Especialidad', icon: LucideTag },
    { key: 'doctorName', label: 'Nombre del Médico', icon: LucideStethoscope },
  ];

  readonly formatOptions: FormatDef[] = [
    {
      value: 'excel',
      label: 'Excel',
      active: 'border-green-600 bg-green-50',
      icon: LucideFileSpreadsheet,
      activeIcon: 'text-green-600',
      activeLabel: 'text-green-700',
    },
    {
      value: 'pdf',
      label: 'PDF',
      active: 'border-red-600 bg-red-50',
      icon: LucideFileText,
      activeIcon: 'text-red-600',
      activeLabel: 'text-red-700',
    },
    {
      value: 'csv',
      label: 'CSV',
      active: 'border-orange-600 bg-orange-50',
      icon: LucideFileText,
      activeIcon: 'text-orange-600',
      activeLabel: 'text-orange-700',
    },
  ];

  // ── Computed ───────────────────────────────────────────────────────────────
  hasSelectedColumns = computed(() =>
    Object.values(this.exportColumns()).some((v) => v)
  );

  private selectedBackendColumns = computed<ExportColumnBackend[]>(() =>
    (Object.entries(this.exportColumns()) as [ExportColumnKey, boolean][])
      .filter(([, v]) => v)
      .map(([k]) => COLUMN_MAP[k])
  );

  colors = computed(() => {
    switch (this.exportFormat()) {
      case 'excel':
        return {
          header: 'bg-green-700',
          border: 'border-green-600',
          bg: 'bg-green-50',
          icon: 'text-green-600',
          button: 'bg-green-600 hover:bg-green-700',
        };
      case 'pdf':
        return {
          header: 'bg-red-700',
          border: 'border-red-600',
          bg: 'bg-red-50',
          icon: 'text-red-600',
          button: 'bg-red-600 hover:bg-red-700',
        };
      default:
        return {
          header: 'bg-orange-700',
          border: 'border-orange-600',
          bg: 'bg-orange-50',
          icon: 'text-orange-600',
          button: 'bg-orange-600 hover:bg-orange-700',
        };
    }
  });

  statusLabel(s: string): string {
    const map: Record<string, string> = {
      AGENDADA: 'Confirmada',
      ATENDIDA: 'Atendida',
      CANCELADA: 'Cancelada',
      NO_ASISTIO: 'No asistió',
      REPROGRAMADA: 'Pendiente',
    };
    return map[s] ?? s;
  }

  accentColor(): string {
    return this.exportFormat() === 'excel'
      ? '#16a34a'
      : this.exportFormat() === 'pdf'
        ? '#dc2626'
        : '#ea580c';
  }

  toggleColumn(key: ExportColumnKey): void {
    this.exportColumns.update((cols) => ({ ...cols, [key]: !cols[key] }));
  }

  isColumnChecked(key: ExportColumnKey): boolean {
    return this.exportColumns()[key];
  }

  close(): void {
    this.closed.emit();
  }

  // ── Export ─────────────────────────────────────────────────────────────────
  private buildPayload(): AppointmentExportRequest {
    const status = this.filterStatus();
    return {
      idDoctor: this.idDoctor() ?? null,
      format: FORMAT_MAP[this.exportFormat()],
      columns: this.selectedBackendColumns(),
      state: status && status !== 'all' ? status : null,
    };
  }

  handleExport(): void {
    if (!this.hasSelectedColumns()) return;

    this.exportingInProgress.set(true);
    this.exportError.set(null);

    const payload = this.buildPayload();
    const fmt = this.exportFormat();
    const today = new Date().toISOString().split('T')[0];

    this.schedulerService.exportAppointments(payload).subscribe({
      next: (blob) => {
        const typedBlob = new Blob([blob], { type: MIME_MAP[fmt] });
        const url = URL.createObjectURL(typedBlob);
        const link = document.createElement('a');
        link.href = url;
        link.download = `Citas_${today}.${EXT_MAP[fmt]}`;
        link.click();
        URL.revokeObjectURL(url);
        this.exportingInProgress.set(false);
        this.closed.emit();
      },
      error: () => {
        this.exportError.set(
          'Ocurrió un error al generar el reporte. Intente nuevamente.'
        );
        this.exportingInProgress.set(false);
      },
    });
  }
}
