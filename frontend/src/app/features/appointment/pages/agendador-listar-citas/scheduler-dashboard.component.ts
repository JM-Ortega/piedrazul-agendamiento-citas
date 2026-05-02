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
import { ExportModalComponent } from '../../../../design-system/organisms/export-modal/export-modal.component';
import { AppointmentsPatient } from '../../../../models/dtos/appointments.dto';
import { dtoDoctor } from '../../../../models/dtos/doctor.dto';
import { SchedulerService } from '../../../../services/scheduler.service';

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

@Component({
  selector: 'app-scheduler-dashboard',
  templateUrl: './scheduler-dashboard.component.html',
  standalone: true,
  imports: [RouterLink, LucideAngularModule, ExportModalComponent],
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
  // ── Filter Helpers ──────────────────────────────────────────────────────────
  private readonly specialtyLabels: Record<string, string> = {
    FISIOTERAPIA: 'Fisioterapia',
    TERAPIA_NEURAL: 'Terapia Neural',
    QUIROPRAXIA: 'Quiropraxia',
  };

  specialtyLabel(specialty: string): string {
    const clean = specialty.replace(/^\[|\]$/g, '').trim();
    return this.specialtyLabels[clean] ?? clean;
  }
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
}
