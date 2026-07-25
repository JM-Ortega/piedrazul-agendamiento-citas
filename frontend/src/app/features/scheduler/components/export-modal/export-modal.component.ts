import {
  Component,
  EventEmitter,
  Input,
  Output,
  computed,
  inject,
  signal,
} from '@angular/core';
import {
  LucideCalendar,
  LucideDownload,
  LucideFileSpreadsheet,
  LucideFileText,
  LucideDynamicIcon,
  type LucideIcon,
} from '@lucide/angular';
import { SchedulerService } from '../../../../core/services/scheduler.service';
import { AppointmentExportRequest } from '../../../../shared/models/dtos/AppointmentExportRequest.dto';
import { ExportFormatBackend } from '../../../../shared/models/types/ExportFormatBackend.type';
import { formatLongDateEs } from '../../../../shared/helpers/date-format';
import { ButtonComponent } from '../../../../design-system/atoms/button.component';

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
  selector: 'app-scheduler-export-modal',
  standalone: true,
  imports: [LucideCalendar, LucideDownload, LucideDynamicIcon, ButtonComponent],
  templateUrl: './export-modal.component.html',
})
export class SchedulerExportModalComponent {
  private schedulerService = inject(SchedulerService);

  @Input({ required: true }) date!: string;

  @Output() exported = new EventEmitter<void>();

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

  formatDate(dateStr: string): string {
    return formatLongDateEs(dateStr);
  }

  open(): void {
    if (!this.date) return;
    this.isCheckingAvailability.set(true);
    this.schedulerService.checkSchedulerAvailability(this.date).subscribe({
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

  private openExportModal(): void {
    this.exportError.set(null);
    this.exportFormat.set('excel');
    this.showExportModal.set(true);
  }

  closeExportModal(): void {
    this.showExportModal.set(false);
    this.exportError.set(null);
  }

  handleExport(): void {
    if (!this.date) return;
    this.isExporting.set(true);
    this.exportError.set(null);

    const fmt = this.exportFormat();
    const payload: AppointmentExportRequest = {
      date: this.date,
      format: FORMAT_MAP[fmt],
    };

    this.schedulerService.exportScheduler(payload).subscribe({
      next: (blob) => {
        const typedBlob = new Blob([blob], { type: MIME_MAP[fmt] });
        const url = URL.createObjectURL(typedBlob);
        const link = document.createElement('a');
        link.href = url;
        link.download = `agenda-${this.date}.${EXT_MAP[fmt]}`;
        link.click();
        URL.revokeObjectURL(url);
        this.isExporting.set(false);
        this.closeExportModal();
        this.exported.emit();
      },
      error: () => {
        this.exportError.set(
          'Ocurrió un error al generar el reporte. Intente nuevamente.'
        );
        this.isExporting.set(false);
      },
    });
  }
}
