import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';
import { Router } from '@angular/router';
import {
  LucideCalendar,
  LucideClock,
  LucideCreditCard,
  LucideDownload,
  LucideFileSpreadsheet,
  LucideFilter,
} from '@lucide/angular';
import { KEYCLOAK_EVENT_SIGNAL } from 'keycloak-angular';
import { DoctorService } from '../../../core/services/doctor.service';
import { SchedulerService } from '../../../core/services/scheduler.service';
import { ExportModalComponent } from '../../../design-system/organisms/export-modal/export-modal.component';
import { AppointmentsPatient } from '../../../shared/models/dtos/appointments.dto';
import { Doctor } from '../../../shared/models/interfaces/doctor.model';

type ExportColumnKey =
  | 'date'
  | 'time'
  | 'patient'
  | 'documentId'
  | 'phone'
  | 'status'
  | 'specialty'
  | 'doctorName';
type FilterDate = 'all' | 'specific' | 'upcoming' | 'past';
type FilterStatus =
  'all' | 'AGENDADA' | 'REPROGRAMADA' | 'CANCELADA' | 'NO_ASISTIO' | 'ATENDIDA';

interface ColumnDef {
  key: ExportColumnKey;
  label: string;
}

@Component({
  selector: 'app-doctor-all-appointments',
  templateUrl: './doctor-all-appointments.component.html',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    LucideCalendar,
    LucideClock,
    LucideCreditCard,
    LucideDownload,
    LucideFileSpreadsheet,
    LucideFilter,
    ExportModalComponent,
  ],
})
export class DoctorAllAppointmentsComponent {
  private router = inject(Router);
  private doctorService = inject(DoctorService);
  private schedulerService = inject(SchedulerService);
  private keycloakEvent = inject(KEYCLOAK_EVENT_SIGNAL);

  // ── State ─────────────────────────────────────────────────────────────────
  today = (() => {
    const d = new Date();
    const yyyy = d.getFullYear();
    const mm = String(d.getMonth() + 1).padStart(2, '0');
    const dd = String(d.getDate()).padStart(2, '0');
    return `${yyyy}-${mm}-${dd}`;
  })();
  currentDoctor = signal<Doctor | null>(null);
  private allAppointments = signal<AppointmentsPatient[]>([]);
  private loaded = signal(false);

  filterStatus = signal<FilterStatus>('all');
  filterDate = signal<FilterDate>('all');
  filterSpecificDate = signal<string>('');
  showExportModal = signal(false);

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
    { key: 'date', label: 'Fecha de la Cita' },
    { key: 'time', label: 'Hora de la Cita' },
    { key: 'patient', label: 'Nombre del Paciente' },
    { key: 'documentId', label: 'Documento de Identidad' },
    { key: 'phone', label: 'Teléfono del Paciente' },
    { key: 'status', label: 'Estado de la Cita' },
    { key: 'specialty', label: 'Especialidad' },
    { key: 'doctorName', label: 'Nombre del Médico' },
  ];

  // ── Computed ──────────────────────────────────────────────────────────────
  hasTodayAppointments = computed(() =>
    this.allAppointments().some(
      (a) => a.date === this.today && a.appointmentState !== 'CANCELADA'
    )
  );
  hasAnyAppointments = computed(() => this.allAppointments().length > 0);
  filteredAppointments = computed(() => {
    let result = this.allAppointments();

    if (this.filterStatus() !== 'all')
      result = result.filter((a) => a.appointmentState === this.filterStatus());

    if (this.filterDate() === 'specific' && this.filterSpecificDate())
      result = result.filter((a) => a.date === this.filterSpecificDate());
    else if (this.filterDate() === 'upcoming')
      result = result.filter(
        (a) => a.date >= this.today && a.appointmentState !== 'CANCELADA'
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
      (a) => a.date >= this.today && a.appointmentState !== 'CANCELADA'
    ).length,
    pending: this.allAppointments().filter(
      (a) => a.appointmentState === 'ATENDIDA'
    ).length,
  }));

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
      AGENDADA: 'bg-green-100 text-green-700 border-green-200',
      REPROGRAMADA: 'bg-yellow-100 text-yellow-700 border-yellow-200',
      CANCELADA: 'bg-red-100 text-red-700 border-red-200',
      NO_ASISTIO: 'bg-orange-100 text-orange-700 border-orange-200',
      ATENDIDA: 'bg-blue-100 text-blue-700 border-blue-200',
    };
    return map[s] ?? 'bg-gray-100 text-gray-700 border-gray-200';
  }
}
