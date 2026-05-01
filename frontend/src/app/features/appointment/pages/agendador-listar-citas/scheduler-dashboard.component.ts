import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import {
  Calendar,
  CheckCircle,
  Clock,
  CreditCard,
  Download,
  FileSpreadsheet,
  FileText,
  LucideAngularModule,
  Phone,
  PlusCircle,
  Search,
  Stethoscope,
  Tag,
  User,
  UserCircle,
} from 'lucide-angular';
import { AppointmentsPatient } from '../../../../models/dtos/appointments.dto';
import { dtoDoctor } from '../../../../models/dtos/doctor.dto';
import {
  AppointmentExportRequest,
  ExportColumnBackend,
  ExportFormatBackend,
  SchedulerService,
} from '../../../../services/scheduler.service';

type ExportColumnKey =
  | 'date'
  | 'time'
  | 'patient'
  | 'documentId'
  | 'phone'
  | 'doctor'
  | 'specialty'
  | 'status';

type ExportColumns = Record<ExportColumnKey, boolean>;

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
  doctor: 'NOMBRE_MEDICO',
  specialty: 'ESPECIALIDAD',
  status: 'ESTADO_CITA',
};

const FORMAT_MAP: Record<'excel' | 'pdf' | 'csv', ExportFormatBackend> = {
  excel: 'EXCEL',
  pdf: 'PDF',
  csv: 'CSV',
};

const MIME_MAP: Record<'excel' | 'pdf' | 'csv', string> = {
  excel: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  pdf: 'application/pdf',
  csv: 'text/csv;charset=utf-8;',
};

const EXT_MAP: Record<'excel' | 'pdf' | 'csv', string> = {
  excel: 'xlsx',
  pdf: 'pdf',
  csv: 'csv',
};

@Component({
  selector: 'app-scheduler-dashboard',
  templateUrl: './scheduler-dashboard.component.html',
  standalone: true,
  imports: [RouterLink, LucideAngularModule],
})
export class SchedulerDashboardComponent implements OnInit {
  private schedulerService = inject(SchedulerService);

  // ── Icons ─────────────────────────────────────────────────────────────────
  readonly Calendar = Calendar;
  readonly CheckCircle = CheckCircle;
  readonly Clock = Clock;
  readonly CreditCard = CreditCard;
  readonly Download = Download;
  readonly FileSpreadsheet = FileSpreadsheet;
  readonly FileText = FileText;
  readonly Phone = Phone;
  readonly PlusCircle = PlusCircle;
  readonly Search = Search;
  readonly Stethoscope = Stethoscope;
  readonly Tag = Tag;
  readonly User = User;
  readonly UserCircle = UserCircle;

  // ── Date helpers ──────────────────────────────────────────────────────────
  today = new Date().toISOString().split('T')[0];
  dayNames = ['Dom', 'Lun', 'Mar', 'Mié', 'Jue', 'Vie', 'Sáb'];
  monthNames = [
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

  // ── Data signals ──────────────────────────────────────────────────────────
  doctors = signal<dtoDoctor[]>([]);
  private appointments = signal<AppointmentsPatient[]>([]);

  // ── Filter / view signals ─────────────────────────────────────────────────
  viewMode = signal<'all' | 'today'>('all');
  filterDate = signal('');
  filterDoctor = signal('');
  filterStatus = signal('');
  searched = signal(false);

  // ── Export signals ────────────────────────────────────────────────────────
  showExportModal = signal(false);
  exportFormat = signal<'excel' | 'pdf' | 'csv'>('excel');
  exportingInProgress = signal(false);
  exportError = signal<string | null>(null);
  exportColumns = signal<ExportColumns>({
    date: true,
    time: true,
    patient: true,
    documentId: true,
    phone: true,
    doctor: true,
    specialty: true,
    status: true,
  });

  readonly columnDefs: ColumnDef[] = [
    { key: 'date', label: 'Fecha de la Cita', icon: Calendar },
    { key: 'time', label: 'Hora de la Cita', icon: Clock },
    { key: 'patient', label: 'Nombre del Paciente', icon: UserCircle },
    { key: 'documentId', label: 'Documento de Identidad', icon: CreditCard },
    { key: 'phone', label: 'Teléfono del Paciente', icon: Phone },
    { key: 'doctor', label: 'Nombre del Médico', icon: Stethoscope },
    { key: 'specialty', label: 'Especialidad', icon: Tag },
    { key: 'status', label: 'Estado de la Cita', icon: CheckCircle },
  ];

  // ── Computed ──────────────────────────────────────────────────────────────
  selectedDoctor = computed(() =>
    this.doctors().find((d) => d.name === this.filterDoctor()),
  );

  todayCount = computed(
    () =>
      this.appointments().filter(
        (a) => a.date === this.today && a.appointmentState !== 'CANCELADA',
      ).length,
  );

  allActiveCount = computed(
    () =>
      this.appointments().filter((a) => a.appointmentState !== 'CANCELADA')
        .length,
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
        (a) => a.appointmentState === this.filterStatus(),
      );
    return [...filtered].sort((a, b) =>
      a.date === b.date
        ? a.startTime > b.startTime
          ? 1
          : -1
        : a.date > b.date
          ? 1
          : -1,
    );
  });

  activeResults = computed(() =>
    this.results().filter((a) => a.appointmentState !== 'CANCELADA'),
  );

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

  search(): void {
    const date = this.filterDate();
    const doctorId = this.filterDoctor();
    let request$;
    if (date && doctorId)
      request$ = this.schedulerService.getAppointmentsByDateAndDoctor(
        date,
        doctorId,
      );
    else if (date) request$ = this.schedulerService.getAppointmentsByDate(date);
    else if (doctorId)
      request$ = this.schedulerService.getAppointmentsByDoctor(doctorId);
    else request$ = this.schedulerService.getAllAppointments();

    request$.subscribe((data) => {
      this.appointments.set(data);
      this.searched.set(true);
    });
  }

  formatDate(dateStr: string): string {
    const d = new Date(dateStr + 'T12:00:00');
    return `${this.dayNames[d.getDay()]} ${d.getDate()} de ${this.monthNames[d.getMonth()]} de ${d.getFullYear()}`;
  }

  statusLabel(s: string): string {
    const map: Record<string, string> = {
      AGENDADA: 'Agendada',
      ATENDIDA: 'Atendida',
      CANCELADA: 'Cancelada',
      NO_ASISTIO: 'No asistió',
      REPROGRAMADA: 'Reprogramada',
    };
    return map[s] ?? s;
  }

  statusColor(s: string): string {
    const map: Record<string, string> = {
      AGENDADA: 'bg-blue-100 text-blue-700',
      ATENDIDA: 'bg-green-100 text-green-700',
      CANCELADA: 'bg-red-100 text-red-700',
      NO_ASISTIO: 'bg-orange-100 text-orange-700',
      REPROGRAMADA: 'bg-yellow-100 text-yellow-700',
    };
    return map[s] ?? '';
  }

  // ── Export helpers ────────────────────────────────────────────────────────
  toggleColumn(key: ExportColumnKey): void {
    this.exportColumns.update((cols) => ({ ...cols, [key]: !cols[key] }));
  }

  isColumnChecked(key: ExportColumnKey): boolean {
    return this.exportColumns()[key];
  }

  accentColor(): string {
    return this.exportFormat() === 'excel'
      ? '#16a34a'
      : this.exportFormat() === 'pdf'
        ? '#dc2626'
        : '#ea580c';
  }

  /**
   * Construye el payload para el backend.
   * - idDoctor → el doctor filtrado actualmente, o null si es "todos"
   * - state    → el estado filtrado actualmente, o null si es "todos"
   * - columns  → solo las columnas seleccionadas en el modal
   */
  private buildPayload(): AppointmentExportRequest {
    const doctor = this.selectedDoctor();
    const status = this.filterStatus();

    return {
      idDoctor: doctor?.id ?? null,
      format: FORMAT_MAP[this.exportFormat()],
      columns: this.selectedBackendColumns(),
      state: status || null,
    };
  }

  handleExport(): void {
    if (!this.hasSelectedColumns()) return;

    this.exportingInProgress.set(true);
    this.exportError.set(null);

    const payload = this.buildPayload();
    const fmt = this.exportFormat();

    this.schedulerService.exportAppointments(payload).subscribe({
      next: (blob) => {
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
