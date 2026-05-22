import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { Router } from '@angular/router';
import {
  Calendar,
  Check,
  Clock,
  FileText,
  LucideAngularModule,
  Plus,
  User,
  UserX,
  ChevronDown,
  ClipboardList,
} from 'lucide-angular';
import { DoctorService } from '../../../core/services/doctor.service';
import { AppointmentsPatient } from '../../../shared/models/dtos/appointments.dto';
import { Doctor } from '../../../shared/models/interfaces/doctor.model';

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
  readonly Check = Check;
  readonly ChevronDown = ChevronDown;
  readonly ClipboardList = ClipboardList;
  readonly UserX = UserX;

  today = (() => {
    const d = new Date();
    const yyyy = d.getFullYear();
    const mm = String(d.getMonth() + 1).padStart(2, '0');
    const dd = String(d.getDate()).padStart(2, '0');
    return `${yyyy}-${mm}-${dd}`;
  })();

  currentDoctor = signal<Doctor | null>(null);
  private appointments = signal<AppointmentsPatient[]>([]);

  showConfirmModal = signal(false);
  selectedAppointmentId = signal<string | null>(null);
  selectedOutcome = signal<'ATENDIDA' | 'NO_ASISTIO' | null>(null);
  isMarkingAttended = signal(false);

  openCardDropdownId = signal<string | null>(null);
  showOutcomeDropdown = signal(false);

  formattedSpecialty = computed(() => {
    const spec = this.currentDoctor()?.specialty ?? '';
    return spec
      .replace(/[\[\]"]/g, '')
      .replace(/_/g, ' ')
      .toLowerCase()
      .replace(/^\w/, (c) => c.toUpperCase());
  });

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
        this.loadAppointments(doctor.id);
      },
      error: () => this.router.navigate(['/']),
    });
  }

  private loadAppointments(doctorId: string): void {
    this.doctorService.getTodayAppointmentsByDoctor(doctorId).subscribe({
      next: (data) => this.appointments.set(data),
      error: () => this.appointments.set([]),
    });
  }

  toggleCardDropdown(appointmentId: string): void {
    this.openCardDropdownId.update((current) =>
      current === appointmentId ? null : appointmentId,
    );
  }

  selectCardOutcome(
    appointmentId: string,
    outcome: 'ATENDIDA' | 'NO_ASISTIO',
  ): void {
    this.openCardDropdownId.set(null);
    this.selectedOutcome.set(outcome);
    this.selectedAppointmentId.set(appointmentId);
    this.showOutcomeDropdown.set(false);
    this.showConfirmModal.set(true);
  }

  toggleOutcomeDropdown(): void {
    this.showOutcomeDropdown.update((v) => !v);
  }

  selectOutcome(value: 'ATENDIDA' | 'NO_ASISTIO'): void {
    this.selectedOutcome.set(value);
    this.showOutcomeDropdown.set(false);
  }

  confirmMarkAsAttended(): void {
    const id = this.selectedAppointmentId();
    const outcome = this.selectedOutcome();
    if (!id || !outcome) return;

    this.isMarkingAttended.set(true);

    this.doctorService.updateAppointmentState(id, outcome).subscribe({
      next: () => {
        this.closeModal();
        const doctorId = this.currentDoctor()?.id;
        if (doctorId) this.loadAppointments(doctorId);
      },
      error: () => {
        this.isMarkingAttended.set(false);
      },
    });
  }

  closeModal(): void {
    this.showConfirmModal.set(false);
    this.selectedAppointmentId.set(null);
    this.selectedOutcome.set(null);
    this.showOutcomeDropdown.set(false);
    this.openCardDropdownId.set(null);
    this.isMarkingAttended.set(false);
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