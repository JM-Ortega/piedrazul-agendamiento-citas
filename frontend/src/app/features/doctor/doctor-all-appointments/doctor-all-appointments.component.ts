import { Component, computed, effect, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { KEYCLOAK_EVENT_SIGNAL } from 'keycloak-angular';
import {
  Calendar,
  CheckCircle,
  Clock,
  CreditCard,
  Download,
  FileSpreadsheet,
  FileText,
  Filter,
  LucideAngularModule,
  Phone,
  UserCircle,
} from 'lucide-angular';
import { AppointmentsPatient } from '../../../models/dtos/appointments.dto';
import { Doctor } from '../../../models/interfaces/doctor.model';
import { DoctorService } from '../../../services/doctor.service';
import {
  AppointmentExportRequest,
  ExportColumnBackend,
  ExportFormatBackend,
  SchedulerService,
} from '../../../services/scheduler.service';

type ExportColumnKey =
  | 'date'
  | 'time'
  | 'patient'
  | 'documentId'
  | 'phone'
  | 'status'
  | 'specialty'
  | 'doctorName';
type ExportColumns = Record<ExportColumnKey, boolean>;
type ExportFormat = 'excel' | 'pdf' | 'csv';
type FilterDate = 'all' | 'specific' | 'upcoming' | 'past';
type FilterStatus =
  | 'all'
  | 'AGENDADA'
  | 'REPROGRAMADA'
  | 'CANCELADA'
  | 'NO_ASISTIO'
  | 'ATENDIDA';

interface ColumnDef {
  key: ExportColumnKey;
  label: string;
  icon: any;
}

/** Mapa frontend-key → columna backend */
const COLUMN_MAP: Record<ExportColumnKey, ExportColumnBackend> = {
  date: 'FECHA_CITA',
  time: 'HORA_CITA',
  patient: 'NOMBRE_PACIENTE',
  documentId: 'DOCUMENTO_IDENTIDAD',
  phone: 'TELEFONO_PACIENTE',
  status: 'ESTADO_CITA',
  specialty: 'ESPECIALIDAD', // ← nuevo
  doctorName: 'NOMBRE_MEDICO', // ← nuevo
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
  selector: 'app-doctor-all-appointments',
  templateUrl: './doctor-all-appointments.component.html',
  standalone: true,
  imports: [LucideAngularModule],
})
export class DoctorAllAppointmentsComponent {
  private router = inject(Router);
  private doctorService = inject(DoctorService);
  private schedulerService = inject(SchedulerService);
  private keycloakEvent = inject(KEYCLOAK_EVENT_SIGNAL);

  // ── Icons ─────────────────────────────────────────────────────────────────
  readonly Calendar = Calendar;
  readonly Clock = Clock;
  readonly FileText = FileText;
  readonly Filter = Filter;
  readonly Download = Download;
  readonly FileSpreadsheet = FileSpreadsheet;
  readonly CreditCard = CreditCard;
  readonly UserCircle = UserCircle;
  readonly CheckCircle = CheckCircle;
  readonly Phone = Phone;

  // ── State ─────────────────────────────────────────────────────────────────
  today = new Date().toISOString().split('T')[0];
  currentDoctor = signal<Doctor | null>(null);
  private allAppointments = signal<AppointmentsPatient[]>([]);
  private loaded = signal(false);
  exportingInProgress = signal(false);
  exportError = signal<string | null>(null);

  filterStatus = signal<FilterStatus>('all');
  filterDate = signal<FilterDate>('all');
  filterSpecificDate = signal<string>('');
  showExportModal = signal(false);
  exportFormat = signal<ExportFormat>('excel');
  exportColumns = signal<ExportColumns>({
    date: true,
    time: true,
    patient: true,
    documentId: true,
    phone: true,
    status: true,
    specialty: true, // ← nuevo
    doctorName: true, // ← nuevo
  });

  readonly monthNames = [
    'enero',
    'febrero',
    'marzo',
    'abril',
    'mayo',
    'junio',
    'julio',
    'agosto',
    'septiembre',
    'octubre',
    'noviembre',
    'diciembre',
  ];

  readonly columnDefs: ColumnDef[] = [
    { key: 'date', label: 'Fecha de la Cita', icon: Calendar },
    { key: 'time', label: 'Hora de la Cita', icon: Clock },
    { key: 'patient', label: 'Nombre del Paciente', icon: UserCircle },
    { key: 'documentId', label: 'Documento de Identidad', icon: CreditCard },
    { key: 'phone', label: 'Teléfono del Paciente', icon: Phone },
    { key: 'status', label: 'Estado de la Cita', icon: CheckCircle },
    { key: 'specialty', label: 'Especialidad', icon: FileText },
    { key: 'doctorName', label: 'Nombre del Médico', icon: UserCircle },
  ];

  // ── Computed ──────────────────────────────────────────────────────────────
  filteredAppointments = computed(() => {
    let result = this.allAppointments();

    if (this.filterStatus() !== 'all')
      result = result.filter((a) => a.appointmentState === this.filterStatus());

    if (this.filterDate() === 'specific' && this.filterSpecificDate())
      result = result.filter((a) => a.date === this.filterSpecificDate());
    else if (this.filterDate() === 'upcoming')
      result = result.filter(
        (a) => a.date >= this.today && a.appointmentState !== 'CANCELADA',
      );
    else if (this.filterDate() === 'past')
      result = result.filter((a) => a.date < this.today);

    return [...result].sort((a, b) => {
      const d = b.date.localeCompare(a.date);
      return d !== 0 ? d : b.startTime.localeCompare(a.startTime);
    });
  });

  stats = computed(() => ({
    total: this.allAppointments().length,
    upcoming: this.allAppointments().filter(
      (a) => a.date >= this.today && a.appointmentState !== 'CANCELADA',
    ).length,
    pending: this.allAppointments().filter(
      (a) => a.appointmentState === 'REPROGRAMADA',
    ).length,
  }));

  hasSelectedColumns = computed(() =>
    Object.values(this.exportColumns()).some((v) => v),
  );

  /** Columnas backend actualmente seleccionadas */
  private selectedBackendColumns = computed<ExportColumnBackend[]>(() =>
    (Object.entries(this.exportColumns()) as [ExportColumnKey, boolean][])
      .filter(([, v]) => v)
      .map(([k]) => COLUMN_MAP[k]),
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

  // ── Constructor ───────────────────────────────────────────────────────────
  constructor() {
    effect(() => {
      this.keycloakEvent();
      if (this.loaded()) return;
      this.loaded.set(true);
      this.loadData();
    });
  }

  // ── Data loading ──────────────────────────────────────────────────────────
  private loadData(): void {
    this.doctorService.getMe().subscribe({
      next: (doctor) => {
        if (!doctor) {
          this.router.navigate(['/']);
          return;
        }
        this.currentDoctor.set(doctor);
        this.schedulerService
          .getAppointmentsByDoctor(doctor.id)
          .subscribe((data) => this.allAppointments.set(data));
      },
      error: () => this.router.navigate(['/']),
    });
  }

  // ── Helpers ───────────────────────────────────────────────────────────────
  formatDate(dateStr: string): string {
    const date = new Date(dateStr + 'T12:00:00');
    return `${date.getDate()} de ${this.monthNames[date.getMonth()]} de ${date.getFullYear()}`;
  }

  getMonthShort(dateStr: string): string {
    return this.monthNames[parseInt(dateStr.split('-')[1]) - 1].slice(0, 3);
  }

  isPast(dateStr: string): boolean {
    return dateStr < this.today;
  }

  statusLabel(s: string): string {
    const map: Record<string, string> = {
      AGENDADA: 'AGENDADA',
      ATENDIDA: 'ATENDIDA',
      CANCELADA: 'CANCELADA',
      NO_ASISTIO: 'NO_ASISTIO',
      REPROGRAMADA: 'REPROGRAMADA',
    };
    return map[s] ?? s;
  }

  statusColor(s: string): string {
    const map: Record<string, string> = {
      AGENDADA: 'bg-green-100 text-green-700 border-green-200',
      REPROGRAMADA: 'bg-yellow-100 text-yellow-700 border-yellow-200',
      CANCELADA: 'bg-red-100 text-red-700 border-red-200',
      NO_ASISTIO: 'bg-orange-100 text-orange-700 border-orange-200',
      ATENDIDA: 'bg-blue-100 text-blue-700 border-blue-200',
    };
    return map[s] ?? 'bg-gray-100 text-gray-700 border-gray-200';
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

  // ── Export ────────────────────────────────────────────────────────────────

  /**
   * Construye el payload para el backend.
   * - format  → siempre la fecha de HOY (ignora filtro de rango global)
   * - state   → filtro de estado activo; null si es 'all'
   * - columns → solo las columnas seleccionadas en el modal
   */
  private buildPayload(): AppointmentExportRequest {
    const doctor = this.currentDoctor();
    const status = this.filterStatus();

    return {
      idDoctor: doctor!.id,
      format: FORMAT_MAP[this.exportFormat()],
      columns: this.selectedBackendColumns(),
      // null omite el filtro de estado en el backend (todos los estados)
      state: status === 'all' ? null : status,
    };
  }

  handleExport(): void {
    if (!this.hasSelectedColumns() || !this.currentDoctor()) return;

    this.exportingInProgress.set(true);
    this.exportError.set(null);

    const payload = this.buildPayload();
    const fmt = this.exportFormat();

    this.schedulerService.exportAppointments(payload).subscribe({
      next: (blob) => {
        // Fuerza el tipo MIME correcto en caso de que el backend no lo envíe
        const typedBlob = new Blob([blob], { type: MIME_MAP[fmt] });
        const url = URL.createObjectURL(typedBlob);
        const link = document.createElement('a');
        link.href = url;
        link.download = `Citas_${this.today}.${EXT_MAP[fmt]}`;
        link.click();
        URL.revokeObjectURL(url);

        this.exportingInProgress.set(false);
        this.showExportModal.set(false);
      },
      error: () => {
        this.exportError.set(
          'Ocurrió un error al generar el reporte. Intente nuevamente.',
        );
        this.exportingInProgress.set(false);
      },
    });
  }
}
