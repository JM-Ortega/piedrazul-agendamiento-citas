import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import {
  Calendar,
  Clock,
  LucideAngularModule,
  PlusCircle,
  Search,
  User,
} from 'lucide-angular';
import { Appointment } from '../../models/appointment.model';
import { Doctor } from '../../models/doctor.model';
import { Patient } from '../../models/patient.model';
import { SchedulerService } from '../../services/scheduler.service';

@Component({
  selector: 'app-scheduler-dashboard',
  templateUrl: './scheduler-dashboard.component.html',
  standalone: true,
  imports: [RouterLink, LucideAngularModule],
})
export class SchedulerDashboardComponent implements OnInit {
  private schedulerService = inject(SchedulerService);

  readonly Calendar = Calendar;
  readonly Clock = Clock;
  readonly User = User;
  readonly Search = Search;
  readonly PlusCircle = PlusCircle;

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

  doctors = signal<Doctor[]>([]);
  private appointments = signal<Appointment[]>([]);

  filterDate = signal(this.today);
  filterDoctor = signal('');
  searched = signal(false);

  selectedDoctor = computed(() =>
    this.doctors().find((d) => d.id === this.filterDoctor()),
  );

  private allAppointments = computed(() =>
    this.appointments()
      .sort((a, b) => (a.time > b.time ? 1 : -1))
      .map((a) => ({
        ...a,
        doctor: this.doctors().find((d) => d.id === a.doctorId),
        patient: undefined as Patient | undefined,
      })),
  );

  results = computed(() =>
    this.allAppointments().filter(
      (a) =>
        (!this.filterDoctor() || a.doctorId === this.filterDoctor()) &&
        (!this.filterDate() || a.date === this.filterDate()),
    ),
  );

  activeResults = computed(() =>
    this.results().filter((a) => a.status !== 'cancelled'),
  );

  ngOnInit(): void {
    this.schedulerService
      .getDoctors()
      .subscribe((data) => this.doctors.set(data));
    this.schedulerService
      .getAppointments()
      .subscribe((data) => this.appointments.set(data));
  }

  search(): void {
    this.schedulerService
      .getAppointments(
        this.filterDoctor() || undefined,
        this.filterDate() || undefined,
      )
      .subscribe((data) => {
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
      confirmed: 'Confirmada',
      pending: 'Pendiente',
      cancelled: 'Cancelada',
    };
    return map[s] ?? s;
  }

  statusColor(s: string): string {
    const map: Record<string, string> = {
      confirmed: 'bg-green-100 text-green-700',
      pending: 'bg-yellow-100 text-yellow-700',
      cancelled: 'bg-red-100 text-red-700',
    };
    return map[s] ?? '';
  }
}
