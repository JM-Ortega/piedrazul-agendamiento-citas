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
import { dtoAppointment } from '../../models/DTOs/dtoAppointment.model';
import { dtoDoctor } from '../../models/DTOs/dtoDoctor.model';
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

  doctors = signal<dtoDoctor[]>([]);
  private appointments = signal<dtoAppointment[]>([]);

  viewMode = signal<'all' | 'today'>('all');
  filterDate = signal('');
  filterDoctor = signal('');
  searched = signal(false);

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

    if (this.viewMode() === 'today') {
      filtered = filtered.filter((a) => a.date === this.today);
    }
    if (this.filterDoctor()) {
      filtered = filtered.filter((a) => a.doctorName === this.filterDoctor());
    }
    if (this.filterDate()) {
      filtered = filtered.filter((a) => a.date === this.filterDate());
    }

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

  ngOnInit(): void {
    this.schedulerService
      .getDoctors()
      .subscribe((data) => this.doctors.set(data));
    this.schedulerService.getAllAppointments().subscribe((data) => {
      this.appointments.set(data);
      this.searched.set(true);
    });
  }
  setViewMode(mode: 'all' | 'today'): void {
    this.viewMode.set(mode);
  }

  search(): void {
    const date = this.filterDate();
    const doctorId = this.filterDoctor();
    let request$;

    if (date && doctorId) {
      request$ = this.schedulerService.getAppointmentsByDateAndDoctor(
        date,
        doctorId,
      );
    } else if (date) {
      request$ = this.schedulerService.getAppointmentsByDate(date);
    } else if (doctorId) {
      request$ = this.schedulerService.getAppointmentsByDoctor(doctorId);
    } else {
      request$ = this.schedulerService.getAllAppointments();
    }

    request$.subscribe((data) => {
      this.appointments.set(data);
      this.searched.set(true);
    });
  }

  clearDoctorFilter(): void {
    this.filterDoctor.set('');
  }

  clearDateFilter(): void {
    this.filterDate.set('');
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
