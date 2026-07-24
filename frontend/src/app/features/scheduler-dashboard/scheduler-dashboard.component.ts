import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import {
  LucideAlertCircle,
  LucideCalendar,
  LucideCheck,
  LucideClock,
  LucideDownload,
  LucideDynamicIcon,
  LucideFileSpreadsheet,
  LucideFileText,
  LucidePlusCircle,
  LucideSearch,
  LucideUser,
  LucideX,
  type LucideIcon,
} from '@lucide/angular';
import { SchedulerService } from '../../core/services/scheduler.service';
import { PatientAppointmentService } from '../../core/services/patientAppointment.service';
import { AppointmentExportRequest } from '../../shared/models/dtos/AppointmentExportRequest.dto';
import { AppointmentsPatient } from '../../shared/models/dtos/appointments.dto';
import { dtoDoctor } from '../../shared/models/dtos/doctor.dto';
import { ExportFormatBackend } from '../../shared/models/types/ExportFormatBackend.type';
import { FormatoPipe } from '../../shared/pipes/formatoPipe';
import { formatLongDateEs } from '../../shared/helpers/date-format';
import {
  APPOINTMENT_STATUS_LABELS,
  APPOINTMENT_STATUS_CLASSES,
} from '../../shared/helpers/appointment-status';
import { ButtonComponent } from '../../design-system/atoms/button.component';

type ExportFormat = 'excel' | 'pdf' | 'csv';

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
  selector: 'app-scheduler-dashboard',
  templateUrl: './scheduler-dashboard.component.html',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    LucideAlertCircle,
    LucideCalendar,
    LucideCheck,
    LucideClock,
    LucideDownload,
    LucideFileSpreadsheet,
    LucidePlusCircle,
    LucideSearch,
    LucideUser,
    LucideX,
    LucideDynamicIcon,
    RouterLink,
    FormatoPipe,
    ButtonComponent,
  ],
})
export class SchedulerDashboardComponent implements OnInit {
  private schedulerService = inject(SchedulerService);
  private patientAppointmentService = inject(PatientAppointmentService);

  // ── Date helpers ──────────────────────────────────────────────────────────
  today = (() => {
    const d = new Date();
    const yyyy = d.getFullYear();
    const mm = String(d.getMonth() + 1).padStart(2, '0');
    const dd = String(d.getDate()).padStart(2, '0');
    return `${yyyy}-${mm}-${dd}`;
  })();

  readonly toastMessage = signal('');
  readonly toastType = signal<'success' | 'error' | null>(null);

  // ── Data signals ──────────────────────────────────────────────────────────
  doctors = signal<dtoDoctor[]>([]);
  private appointments = signal<AppointmentsPatient[]>([]);

  // ── Filter / view signals ─────────────────────────────────────────────────
  viewMode = signal<'all' | 'today'>('all');
  filterDate = signal('');
  filterDoctor = signal('');
  filterStatus = signal('');
  searched = signal(false);
  hoveredView = signal<string | null>(null);

  // ── Export signals ────────────────────────────────────────────────────────
  showCancelModal = signal(false);
  pendingCancelId = signal<string | null>(null);

  showExportModal = signal(false);
  exportFormat = signal<ExportFormat>('excel');
  isExporting = signal(false);
  exportError = signal<string | null>(null);
  showAvailabilityWarning = signal(false);
  isCheckingAvailability = signal(false);

  readonly formatOptions: {
    value: ExportFormat;
    label: string;
    icon: LucideIcon;
    active: string;
    activeIcon: string;
    activeLabel: string;
  }[] = [
    {
      value: 'excel',
      label: 'Excel',
      icon: LucideFileSpreadsheet,
      active: 'border-green-600 bg-green-50',
      activeIcon: 'text-green-600',
      activeLabel: 'text-green-700',
    },
    {
      value: 'pdf',
      label: 'PDF',
      icon: LucideFileText,
      active: 'border-red-600 bg-red-50',
      activeIcon: 'text-red-600',
      activeLabel: 'text-red-700',
    },
    {
      value: 'csv',
      label: 'CSV',
      icon: LucideFileText,
      active: 'border-orange-600 bg-orange-50',
      activeIcon: 'text-orange-600',
      activeLabel: 'text-orange-700',
    },
  ];

  // ── Computed ──────────────────────────────────────────────────────────────
  selectedDoctor = computed(() =>
    this.doctors().find((d) => d.name === this.filterDoctor())
  );

  todayCount = computed(
    () =>
      this.appointments().filter(
        (a) => a.date === this.today && a.appointmentState !== 'CANCELADA'
      ).length
  );

  allActiveCount = computed(
    () =>
      this.appointments().filter((a) => a.appointmentState !== 'CANCELADA')
        .length
  );

  results = computed(() => {
    let filtered = this.appointments();
    if (this.viewMode() === 'today')
      filtered = filtered.filter((a) => a.date === this.today);
    if (this.filterDoctor())
      filtered = filtered.filter((a) => a.doctorName === this.filterDoctor());
    if (this.filterDate())
      filtered = filtered.filter((a) => a.date === this.filterDate());
    if (this.filterStatus())
      filtered = filtered.filter(
        (a) => a.appointmentState === this.filterStatus()
      );
    const stateOrder: Record<string, number> = {
      AGENDADA: 1,
      ATENDIDA: 2,
      CANCELADA: 3,
    };
    return [...filtered].sort((a, b) => {
      const stateDiff =
        stateOrder[a.appointmentState] - stateOrder[b.appointmentState];
      if (stateDiff !== 0) return stateDiff;
      const dateTimeA = new Date(`${a.date}T${a.startTime}`);
      const dateTimeB = new Date(`${b.date}T${b.startTime}`);
      return dateTimeA.getTime() - dateTimeB.getTime();
    });
  });

  activeResults = computed(() =>
    this.results().filter((a) => a.appointmentState !== 'CANCELADA')
  );

  exportColors = computed(() => {
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

  // ── Lifecycle ─────────────────────────────────────────────────────────────
  ngOnInit(): void {
    this.schedulerService
      .getDoctors()
      .subscribe((data) => this.doctors.set(data));
    this.schedulerService.getAllAppointments().subscribe((data) => {
      this.appointments.set(data);
      this.searched.set(true);
    });
  }

  // ── View / filter ─────────────────────────────────────────────────────────
  setViewMode(mode: 'all' | 'today'): void {
    this.viewMode.set(mode);
  }

  clearDoctorFilter(): void {
    this.filterDoctor.set('');
  }

  clearDateFilter(): void {
    this.filterDate.set('');
  }

  clearStatusFilter(): void {
    this.filterStatus.set('');
  }

  // ── Export ────────────────────────────────────────────────────────────────
  openExportModal(): void {
    this.exportError.set(null);
    this.exportFormat.set('excel');
    this.showExportModal.set(true);
  }

  handleExportClick(): void {
    const date = this.filterDate();
    if (!date) return;

    this.isCheckingAvailability.set(true);

    this.schedulerService.checkSchedulerAvailability(date).subscribe({
      next: ({ hasAvailabilitySlots }) => {
        this.isCheckingAvailability.set(false);
        if (hasAvailabilitySlots) {
          this.showAvailabilityWarning.set(true);
        } else {
          this.openExportModal();
        }
      },
      error: () => {
        this.isCheckingAvailability.set(false);
        this.openExportModal();
      },
    });
  }
  confirmExportDespiteAvailability(): void {
    this.showAvailabilityWarning.set(false);
    this.openExportModal();
  }

  closeExportModal(): void {
    this.showExportModal.set(false);
    this.exportError.set(null);
  }

  handleExport(): void {
    const date = this.filterDate();
    if (!date) return;

    this.isExporting.set(true);
    this.exportError.set(null);

    const fmt = this.exportFormat();
    const payload: AppointmentExportRequest = {
      date,
      format: FORMAT_MAP[fmt],
    };

    this.schedulerService.exportScheduler(payload).subscribe({
      next: (blob) => {
        const typedBlob = new Blob([blob], { type: MIME_MAP[fmt] });
        const url = URL.createObjectURL(typedBlob);
        const link = document.createElement('a');
        link.href = url;
        link.download = `agenda-${date}.${EXT_MAP[fmt]}`;
        link.click();
        URL.revokeObjectURL(url);
        this.isExporting.set(false);
        this.closeExportModal();
      },
      error: () => {
        this.exportError.set(
          'Ocurrió un error al generar el reporte. Intente nuevamente.'
        );
        this.isExporting.set(false);
      },
    });
  }

  requestCancelAppointment(appointmentId: string): void {
    this.pendingCancelId.set(appointmentId);
    this.showCancelModal.set(true);
  }

  confirmCancelAppointment(): void {
    const appointmentId = this.pendingCancelId();
    if (!appointmentId) return;
    this.showCancelModal.set(false);
    this.pendingCancelId.set(null);
    this.patientAppointmentService.cancelAppointment(appointmentId).subscribe({
      next: () => {
        this.showToast('La cita fue cancelada exitosamente', 'success');
        this.appointments.set(
          this.appointments().map((a) =>
            a.idAppointment === appointmentId
              ? { ...a, appointmentState: 'CANCELADA' }
              : a
          )
        );
      },
      error: () => {
        this.showToast('Ocurrió un error al cancelar la cita', 'error');
      },
    });
  }

  dismissCancelModal(): void {
    this.showCancelModal.set(false);
    this.pendingCancelId.set(null);
  }

  private showToast(message: string, type: 'success' | 'error'): void {
    this.toastMessage.set(message);
    this.toastType.set(type);
    setTimeout(() => {
      this.toastMessage.set('');
      this.toastType.set(null);
    }, 3000);
  }

  // ── Helpers ───────────────────────────────────────────────────────────────
  formatDate(dateStr: string): string {
    return formatLongDateEs(dateStr);
  }

  statusLabel(s: string): string {
    return (
      APPOINTMENT_STATUS_LABELS[s as AppointmentsPatient['appointmentState']] ??
      s
    );
  }

  statusColor(s: string): string {
    return (
      APPOINTMENT_STATUS_CLASSES[
        s as AppointmentsPatient['appointmentState']
      ] ?? ''
    );
  }
}
