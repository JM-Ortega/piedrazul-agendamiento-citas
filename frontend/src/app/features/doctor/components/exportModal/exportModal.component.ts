import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
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
import { catchError, combineLatest, of, switchMap, tap } from 'rxjs';
import { DoctorService } from '../../../../core/services/doctor.service';
import { SchedulerService } from '../../../../core/services/scheduler.service';
import { ButtonComponent } from '../../../../designSystem/atoms/button/button.component';
import { AppointmentExportRequest } from '../../../../shared/models/dtos/AppointmentExportRequest.dto';
import { AppointmentsPatient } from '../../../../shared/models/dtos/appointments.dto';
import { ExportColumnBackend } from '../../../../shared/models/types/ExportColumnBackend.type';
import { ExportFormatBackend } from '../../../../shared/models/types/ExportFormatBackend.type';

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
type ExportStep = 1 | 2;

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

const EXPORT_STATUSES = [
  'AGENDADA',
  'REPROGRAMADA',
  'CANCELADA',
  'NO_ASISTIO',
  'ATENDIDA',
] as const;

@Component({
  selector: 'app-export-modal',
  templateUrl: './exportModal.component.html',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [LucideDynamicIcon, LucideDownload, LucideCalendar, ButtonComponent],
})
export class ExportModalComponent {
  private schedulerService = inject(SchedulerService);
  private doctorService = inject(DoctorService);
  private destroyRef = inject(DestroyRef);

  // ── Inputs desde el padre ──────────────────────────────────────────────────

  /** ID del doctor. Si es null → no filtra por doctor (agendador sin filtro) */
  idDoctor = input<string | null>(null);

  /** Nombre legible del doctor filtrado (para mostrar en el aviso) */
  doctorName = input<string | null>(null);

  /** Fecha de hoy formateada para mostrar en el aviso */
  todayFormatted = input.required<string>();

  /** Citas de hoy (todos los estados), para calcular conteos por estado */
  appointments = input<AppointmentsPatient[]>([]);

  // ── Output ─────────────────────────────────────────────────────────────────
  /** Emite cuando el modal debe cerrarse (éxito o cancelación) */
  closed = output<void>();

  // ── Estado interno ─────────────────────────────────────────────────────────
  currentStep = signal<ExportStep>(1);
  exportStatusFilter = signal<string>('all');

  hasAvailability = signal(false);

  /** True mientras se resuelve la verificación de disponibilidad (evita saltos de UI y dobles clics). */
  checkingExistence = signal(false);

  /** Error al verificar disponibilidad contra el backend (distinto del error de exportación). */
  availabilityError = signal<string | null>(null);

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
      active: 'border-[#215c98] bg-[#eaf1f8]',
      icon: LucideFileText,
      activeIcon: 'text-[#215c98]',
      activeLabel: 'text-[#163c63]',
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

  readonly statusLabels: Record<string, string> = {
    all: 'Todos los estados',
    AGENDADA: 'Confirmadas',
    REPROGRAMADA: 'Reprogramadas',
    CANCELADA: 'Canceladas',
    NO_ASISTIO: 'No asistió',
    ATENDIDA: 'Atendidas',
  };

  /** Opciones de estado para el select, en el orden en que deben mostrarse. */
  readonly statusOptions: string[] = ['all', ...EXPORT_STATUSES];

  // ── Constructor: verificación reactiva de disponibilidad ─────────────────

  constructor() {
    combineLatest([
      toObservable(this.exportStatusFilter),
      toObservable(this.appointments),
    ])
      .pipe(
        tap(() => {
          this.checkingExistence.set(true);
          this.availabilityError.set(null);
        }),
        switchMap(([status, appointments]) => {
          if (status === 'all') {
            return of(appointments.length > 0);
          }

          const doctorId = this.idDoctor();
          if (!doctorId) {
            return of(appointments.some((a) => a.appointmentState === status));
          }

          return this.doctorService
            .checkExistenceByDoctorAndState(doctorId, status)
            .pipe(
              catchError(() => {
                this.availabilityError.set(
                  'No se pudo verificar la disponibilidad de citas. Intente nuevamente.'
                );
                return of(false);
              })
            );
        }),
        tap(() => this.checkingExistence.set(false)),
        takeUntilDestroyed(this.destroyRef)
      )
      .subscribe((hasAvailability) =>
        this.hasAvailability.set(hasAvailability)
      );
  }

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
          header: 'bg-[#1f7a52]',
          border: 'border-[#1f7a52]',
          bg: 'bg-[#e6f5ee]',
          icon: 'text-[#1f7a52]',
          button: 'bg-[#1f7a52] hover:bg-[#166345]',
        };
      case 'pdf':
        return {
          header: 'bg-[#215c98]',
          border: 'border-[#215c98]',
          bg: 'bg-[#eaf1f8]',
          button: 'bg-[#215c98] hover:bg-[#163c63]',
        };
      default:
        return {
          header: 'bg-[#b86a2d]',
          border: 'border-[#b86a2d]',
          bg: 'bg-[#faf1e8]',
          icon: 'text-[#b86a2d]',
          button: 'bg-[#b86a2d] hover:bg-[#96551f]',
        };
    }
  });

  // ── Navegación entre pasos ────────────────────────────────────────────────
  goToStep2(): void {
    if (!this.hasAvailability() || this.checkingExistence()) return;
    this.currentStep.set(2);
  }

  goToStep1(): void {
    this.currentStep.set(1);
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
    const status = this.exportStatusFilter();
    return {
      idDoctor: this.idDoctor() ?? null,
      format: FORMAT_MAP[this.exportFormat()],
      columns: this.selectedBackendColumns(),
      state: status !== 'all' ? status : null,
    };
  }

  handleExport(): void {
    if (!this.hasSelectedColumns() || !this.hasAvailability()) return;

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
