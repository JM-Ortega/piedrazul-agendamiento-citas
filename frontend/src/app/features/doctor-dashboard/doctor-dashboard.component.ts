import {
  Component,
  computed,
  effect,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { Router } from '@angular/router';
import { KEYCLOAK_EVENT_SIGNAL } from 'keycloak-angular';
import {
  Calendar,
  Clock,
  FileText,
  LucideAngularModule,
  Phone,
  User,
} from 'lucide-angular';
import { AppointmentsPatient } from '../../models/dtos/appointments.dto';
import { Doctor } from '../../models/interfaces/doctor.model';
import { AppService } from '../../services/app.service';
import { DoctorService } from '../../services/doctor.service';

@Component({
  selector: 'app-doctor-dashboard',
  templateUrl: './doctor-dashboard.component.html',
  standalone: true,
  imports: [LucideAngularModule],
})
export class DoctorDashboardComponent implements OnInit {
  private doctorService = inject(DoctorService);
  private appService = inject(AppService);
  private router = inject(Router);
  private keycloakEvent = inject(KEYCLOAK_EVENT_SIGNAL);

  // ── Lucide icons ──────────────────────────────────────────────────────────
  readonly Calendar = Calendar;
  readonly Clock = Clock;
  readonly FileText = FileText;
  readonly Phone = Phone;
  readonly User = User;

  // ── Signals ───────────────────────────────────────────────────────────────
  today = new Date().toISOString().split('T')[0];
  currentDoctor = signal<Doctor | null>(null);
  private appointments = signal<AppointmentsPatient[]>([]);
  private loaded = signal(false); // ← evita llamadas duplicadas

  // ── Computed ──────────────────────────────────────────────────────────────
  todaysAppointments = computed(() =>
    [...this.appointments()]
      .filter(
        (a) => a.date === this.today && a.appointmentState !== 'CANCELADA',
      )
      .sort((a, b) => a.startTime.localeCompare(b.startTime)),
  );

  totalCount = computed(() => this.todaysAppointments().length);
  confirmedCount = computed(
    () =>
      this.todaysAppointments().filter((a) => a.appointmentState === 'AGENDADA')
        .length,
  );
  pendingCount = computed(
    () =>
      this.todaysAppointments().filter(
        (a) => a.appointmentState === 'REPROGRAMADA',
      ).length,
  );

  constructor() {
    effect(() => {
      const event = this.keycloakEvent();
      const keycloakId = this.appService.keycloakId();

      console.log('event type:', event.type);
      console.log('keycloakId:', keycloakId);
      console.log('loaded:', this.loaded());

      if (!keycloakId || this.loaded()) return;

      this.loaded.set(true);
      this.loadDoctor(keycloakId);
    });
  }

  ngOnInit(): void {}

  // ── Data loading ──────────────────────────────────────────────────────────
  private loadDoctor(keycloakId: string): void {
    console.log('loadDoctor llamado con:', keycloakId);

    this.doctorService.getMe(keycloakId).subscribe({
      next: (doctor) => {
        console.log('doctor recibido:', doctor);
        if (!doctor) {
          console.log('doctor undefined → redirigiendo');
          this.router.navigate(['/']);
          return;
        }
        this.currentDoctor.set(doctor);
        this.doctorService
          .getTodayAppointmentsByDoctor(doctor.id)
          .subscribe((data) => {
            console.log('appointments:', data);
            this.appointments.set(data);
          });
      },
      error: (err) => {
        console.log('error en getMe:', err);
        this.router.navigate(['/']);
      },
    });
  }

  // ── Helpers ───────────────────────────────────────────────────────────────
  formatDate(dateStr: string): string {
    const date = new Date(dateStr + 'T12:00:00');
    return new Intl.DateTimeFormat('es-CO', {
      weekday: 'long',
      year: 'numeric',
      month: 'long',
      day: 'numeric',
    }).format(date);
  }

  statusColor(state: string): string {
    const map: Record<string, string> = {
      AGENDADA: 'bg-green-100 text-green-800 border-green-300',
      REPROGRAMADA: 'bg-yellow-100 text-yellow-800 border-yellow-300',
      CANCELADA: 'bg-gray-100 text-gray-800 border-gray-300',
      NO_ASISTIO: 'bg-orange-100 text-orange-800 border-orange-300',
      ATENDIDA: 'bg-blue-100 text-blue-800 border-blue-300',
    };
    return map[state] ?? 'bg-gray-100 text-gray-800 border-gray-300';
  }

  statusLabel(state: string): string {
    const map: Record<string, string> = {
      AGENDADA: 'Confirmada',
      ATENDIDA: 'Atendida',
      CANCELADA: 'Cancelada',
      NO_ASISTIO: 'No asistió',
      REPROGRAMADA: 'Pendiente',
    };
    return map[state] ?? state;
  }

  goToHistory(patientId: number | string): void {
    this.router.navigate(['/medico/historia', patientId]);
  }
}
