import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { Router } from '@angular/router';
import {
  Calendar,
  Clock,
  FileText,
  LucideAngularModule,
  Plus,
  User,
} from 'lucide-angular';
import { AppointmentsPatient } from '../../../models/dtos/appointments.dto';
import { Doctor } from '../../../models/interfaces/doctor.model';
import { DoctorService } from '../../../services/doctor.service';

@Component({
  selector: 'app-doctor-dashboard',
  templateUrl: './doctor-dashboard.component.html',
  standalone: true,
  imports: [LucideAngularModule],
})
export class DoctorDashboardComponent implements OnInit {
  private doctorService = inject(DoctorService);
  private router = inject(Router);

  readonly Calendar = Calendar;
  readonly Clock = Clock;
  readonly FileText = FileText;
  readonly User = User;
  readonly Plus = Plus;

  today = (() => {
    const d = new Date();
    const yyyy = d.getFullYear();
    const mm = String(d.getMonth() + 1).padStart(2, '0');
    const dd = String(d.getDate()).padStart(2, '0');
    return `${yyyy}-${mm}-${dd}`;
  })();
  currentDoctor = signal<Doctor | null>(null);

  formattedSpecialty = computed(() => {
    const spec = this.currentDoctor()?.specialty ?? '';
    return spec
      .replace(/[\[\]"]/g, '')
      .replace(/_/g, ' ')
      .toLowerCase()
      .replace(/^\w/, (c) => c.toUpperCase());
  });
  private appointments = signal<AppointmentsPatient[]>([]);

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

  ngOnInit(): void {
    this.doctorService.getMe().subscribe({
      next: (doctor) => {
        this.currentDoctor.set(doctor);
        this.doctorService.getTodayAppointmentsByDoctor(doctor.id).subscribe({
          next: (data) => this.appointments.set(data),
          error: () => this.appointments.set([]),
        });
      },
      error: () => this.router.navigate(['/']),
    });
  }

  scheduleNewAppointment(documentNumber: string): void {
    this.router.navigate(['/medico/nueva-cita'], {
      queryParams: { documentNumber },
    });
  }

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
}
