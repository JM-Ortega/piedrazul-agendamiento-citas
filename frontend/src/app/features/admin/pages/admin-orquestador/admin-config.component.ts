import {
  Component,
  computed,
  inject,
  OnInit,
  signal,
  ChangeDetectionStrategy,
} from '@angular/core';
import { LucideAngularModule, Pencil, Settings } from 'lucide-angular';
import { forkJoin, Observable } from 'rxjs';
import { dtoSchedule } from '../../../../shared/models/dtos/schedule.dto';
import { DaySchedule } from '../../../../shared/models/interfaces/daySchedule.model';
import { Doctor } from '../../../../shared/models/interfaces/doctor.model';
import { DoctorCardComponent } from '../../components/doctor-card/doctor-card.component';
import {
  DoctorEditFormComponent,
  DoctorSaveEvent,
} from '../../components/doctor-edit-form/doctor-edit-form.component';
import { AdminModalsComponent } from '../../components/modals/modals-horarios/admin-modals.component';
import { AdminService } from '../../service/admin.service';

@Component({
  selector: 'app-admin-config',
  templateUrl: './admin-config.component.html',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.Eager,
  imports: [
    LucideAngularModule,
    DoctorCardComponent,
    DoctorEditFormComponent,
    AdminModalsComponent,
  ],
})
export class AdminConfigComponent implements OnInit {
  private adminService = inject(AdminService);

  readonly Settings = Settings;
  readonly Pencil = Pencil;

  private readonly DAY_TO_WORKDAY: Record<number, string> = {
    1: 'LUNES',
    2: 'MARTES',
    3: 'MIERCOLES',
    4: 'JUEVES',
    5: 'VIERNES',
  };

  // ── State ─────────────────────────────────────────────────────────────────
  doctors = signal<Doctor[]>([]);
  loading = signal(false);
  errorCarga = signal('');
  editingId = signal<string | null>(null);
  savedId = signal<string | null>(null);

  showConfirmModal = signal(false);
  doctorToToggle = signal<Doctor | null>(null);
  showForceModal = signal(false);
  forceModalMessage = signal('');
  showErrorModal = signal(false);
  errorGuardado = signal('');

  // ── Computed ──────────────────────────────────────────────────────────────
  totalSpecialties = computed(
    () => new Set(this.doctors().map((d) => d.specialty)).size
  );
  avgInterval = computed(() => {
    const docs = this.doctors();
    return docs.length
      ? Math.round(
          docs.reduce((acc, d) => acc + d.appointmentInterval, 0) / docs.length
        )
      : 0;
  });

  // ── Lifecycle ─────────────────────────────────────────────────────────────
  ngOnInit(): void {
    this.loadDoctors();
  }

  // ── Data loading ──────────────────────────────────────────────────────────
  loadDoctors(): void {
    this.loading.set(true);
    this.errorCarga.set('');
    this.adminService.getDoctors().subscribe({
      next: (doctors) => {
        forkJoin(
          doctors.map((d) => this.adminService.getSchedulesByDoctor(d.id))
        ).subscribe({
          next: (allSchedules) => {
            this.doctors.set(
              doctors.map((d, i) => ({
                ...d,
                workdays: d.workdays ?? [],
                ...this.mapSchedulesToDoctor(allSchedules[i]),
              }))
            );
            this.loading.set(false);
          },
          error: () => {
            this.doctors.set(
              doctors.map((d) => ({ ...d, workdays: d.workdays ?? [] }))
            );
            this.loading.set(false);
          },
        });
      },
      error: () => {
        this.errorCarga.set('Error al cargar los médicos. Intente de nuevo.');
        this.loading.set(false);
      },
    });
  }

  // ── Edit handlers ─────────────────────────────────────────────────────────
  startEdit(doctor: Doctor): void {
    this.editingId.set(doctor.id);
    this.savedId.set(null);
  }

  cancelEdit(): void {
    this.editingId.set(null);
  }

  onFormSaved(event: DoctorSaveEvent): void {
    const { form, originalDoctor, originalWorkdays, removedWorkdays } = event; // ← añadir removedWorkdays
    const calls: Observable<unknown>[] = [];

    if (originalDoctor.appointmentInterval !== form.appointmentInterval)
      calls.push(
        this.adminService.updateAppointmentInterval(
          form.id,
          form.appointmentInterval
        )
      );
    if (originalDoctor.laborStart !== form.laborStart)
      calls.push(this.adminService.updateLaborStart(form.id, form.laborStart));
    if (originalDoctor.laborEnd !== form.laborEnd)
      calls.push(this.adminService.updateLaborEnd(form.id, form.laborEnd));

    removedWorkdays.forEach((day) => {
      const workday = this.DAY_TO_WORKDAY[day];
      if (workday)
        calls.push(this.adminService.deleteSchedule(form.id, workday));
    });

    (form.workdays ?? []).forEach((day) => {
      const workday = this.DAY_TO_WORKDAY[day];
      const ds = form.daySchedules?.[day];
      const startTime = this.toTimeBackend(
        ds?.startTime ?? form.startTime ?? '05:00'
      );
      const endTime = this.toTimeBackend(
        ds?.endTime ?? form.endTime ?? '12:00'
      );
      if (!originalWorkdays.includes(day))
        calls.push(
          this.adminService.createSchedule(form.id, workday, startTime, endTime)
        );
      else
        calls.push(
          this.adminService.updateSchedule(form.id, workday, startTime, endTime)
        );
    });

    if (!calls.length) {
      this.cancelEdit();
      return;
    }

    forkJoin(calls).subscribe({
      next: () => {
        this.reloadDoctor(form.id, form);
        this.savedId.set(form.id);
        this.editingId.set(null);
        setTimeout(() => this.savedId.set(null), 3000);
      },
      error: (err) => {
        const raw: string =
          err?.error?.detail ??
          'Error al guardar los cambios. Intente de nuevo.';
        this.errorGuardado.set(
          raw.startsWith('User is already active')
            ? 'El médico ya está trabajando activamente. Debe deshabilitarlo primero para poder cambiar su período laboral.'
            : raw
        );
        this.showErrorModal.set(true);
      },
    });
  }

  // ── Toggle handlers ───────────────────────────────────────────────────────
  openToggleModal(doctor: Doctor): void {
    this.doctorToToggle.set(doctor);
    this.showConfirmModal.set(true);
  }

  onCloseToggleModal(): void {
    this.showConfirmModal.set(false);
    this.doctorToToggle.set(null);
  }

  onConfirmToggle(): void {
    const doctor = this.doctorToToggle();
    if (!doctor) return;
    if (!doctor.status) {
      this.adminService
        .enableDoctor(doctor.id, doctor.laborStart, doctor.laborEnd)
        .subscribe({
          next: () => {
            this.doctors.update((list) =>
              list.map((d) => (d.id === doctor.id ? { ...d, status: true } : d))
            );
            this.onCloseToggleModal();
          },
          error: (err) => {
            this.forceModalMessage.set(
              err?.error?.detail ?? 'Error al habilitar el médico.'
            );
            this.onCloseToggleModal();
            this.showForceModal.set(true);
          },
        });
      return;
    }
    this.adminService.disableDoctor(doctor.id, false).subscribe({
      next: () => {
        this.doctors.update((list) =>
          list.map((d) => (d.id === doctor.id ? { ...d, status: false } : d))
        );
        this.onCloseToggleModal();
      },
      error: (err) => {
        this.forceModalMessage.set(
          err?.error?.detail ??
            'El médico tiene restricciones para ser deshabilitado.'
        );
        this.showConfirmModal.set(false);
        this.showForceModal.set(true);
      },
    });
  }

  onConfirmForceDisable(): void {
    const target = this.doctorToToggle();
    if (!target) return;
    this.adminService.disableDoctor(target.id, true).subscribe({
      next: () => {
        this.doctors.update((list) =>
          list.map((d) => (d.id === target.id ? { ...d, status: false } : d))
        );
        this.showForceModal.set(false);
        this.forceModalMessage.set('');
        this.doctorToToggle.set(null);
      },
      error: (err) => {
        this.forceModalMessage.set(
          err?.error?.detail ?? 'Error al forzar la deshabilitación.'
        );
      },
    });
  }

  onCancelForceDisable(): void {
    this.showForceModal.set(false);
    this.doctorToToggle.set(null);
    this.forceModalMessage.set('');
  }

  onCloseErrorModal(): void {
    this.showErrorModal.set(false);
    this.errorGuardado.set('');
  }

  // ── Helpers de template ───────────────────────────────────────────────────
  containerClass(doctor: Doctor): string {
    if (doctor.status === false) return 'border-gray-200 bg-gray-50 opacity-60';
    if (this.editingId() === doctor.id) return 'border-blue-400 bg-blue-50';
    return 'border-gray-100 bg-white hover:shadow-lg transition-shadow';
  }

  // ── Private ───────────────────────────────────────────────────────────────
  private toTimeBackend(time: string | undefined): string {
    if (!time) return '';
    return time.length === 5 ? `${time}:00` : time;
  }

  private mapSchedulesToDoctor(schedules: dtoSchedule[]): Partial<Doctor> {
    if (!schedules?.length) return {};
    const daySchedules: Record<number, DaySchedule> = {};
    const workdays: number[] = [];
    schedules.forEach((s) => {
      const day = this.workdayToNumber(s.workday);
      if (day === null) return;
      daySchedules[day] = {
        startTime: s.startTime.substring(0, 5),
        endTime: s.endTime.substring(0, 5),
      };
      workdays.push(day);
    });
    workdays.sort((a, b) => a - b);
    return {
      startTime: schedules[0].startTime.substring(0, 5),
      endTime: schedules[0].endTime.substring(0, 5),
      daySchedules,
      workdays,
    };
  }

  private workdayToNumber(workday: dtoSchedule['workday']): number | null {
    const map: Record<string, number> = {
      LUNES: 1,
      MARTES: 2,
      MIERCOLES: 3,
      JUEVES: 4,
      VIERNES: 5,
    };
    return map[workday] ?? null;
  }

  private reloadDoctor(doctorId: string, fallback: Doctor): void {
    forkJoin([
      this.adminService.getDoctors(),
      this.adminService.getSchedulesByDoctor(doctorId),
    ]).subscribe({
      next: ([doctors, schedules]) => {
        const freshDoctor = doctors.find((d) => d.id === doctorId) ?? fallback;
        const mapped = this.mapSchedulesToDoctor(schedules);
        this.doctors.update((list) =>
          list.map((d) =>
            d.id === doctorId ? { ...freshDoctor, ...mapped } : d
          )
        );
      },
      error: () => {
        this.doctors.update((list) =>
          list.map((d) => (d.id === doctorId ? fallback : d))
        );
      },
    });
  }
}
