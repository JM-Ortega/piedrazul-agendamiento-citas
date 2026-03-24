import { Component, computed, inject, OnInit, signal } from '@angular/core';
import {
  Calendar,
  CheckCircle,
  ChevronDown,
  ChevronUp,
  Clock,
  Edit3,
  LucideAngularModule,
  Power,
  PowerOff,
  Save,
  Settings,
  X,
} from 'lucide-angular';
import { forkJoin } from 'rxjs';
import { DaySchedule } from '../../models/daySchedule.model';
import { Doctor } from '../../models/doctor.model';
import { dtoSchedule } from '../../models/DTOs/dtoSchedule.model';
import { AdminService } from '../../services/admin.service';

@Component({
  selector: 'app-admin-config',
  templateUrl: './admin-config.component.html',
  standalone: true,
  imports: [LucideAngularModule],
})
export class AdminConfigComponent implements OnInit {
  private adminService = inject(AdminService);

  readonly Settings = Settings;
  readonly Edit3 = Edit3;
  readonly Save = Save;
  readonly Clock = Clock;
  readonly Calendar = Calendar;
  readonly CheckCircle = CheckCircle;
  readonly ChevronDown = ChevronDown;
  readonly ChevronUp = ChevronUp;
  readonly Power = Power;
  readonly PowerOff = PowerOff;
  readonly X = X;

  readonly DAY_LABELS = ['Dom', 'Lun', 'Mar', 'Mié', 'Jue', 'Vie', 'Sáb'];
  readonly DAY_FULL_LABELS = [
    'Domingo',
    'Lunes',
    'Martes',
    'Miércoles',
    'Jueves',
    'Viernes',
    'Sábado',
  ];
  readonly weekDays = [1, 2, 3, 4, 5];

  private readonly DAY_TO_WORKDAY: { [day: number]: string } = {
    1: 'LUNES',
    2: 'MARTES',
    3: 'MIERCOLES',
    4: 'JUEVES',
    5: 'VIERNES',
  };

  doctors = signal<Doctor[]>([]);
  loading = signal(false);
  errorCarga = signal('');

  editingId = signal<string | null>(null);
  editForm = signal<Doctor | null>(null);
  savedId = signal<string | null>(null);
  showDaySchedules = signal(false);
  showConfirmModal = signal(false);
  doctorToToggle = signal<Doctor | null>(null);
  errorHorarioGlobal = signal('');
  errorHorariosDia = signal<{ [day: number]: string }>({});
  errorFechas = signal('');
  showForceModal = signal(false);
  forceModalMessage = signal('');

  private originalWorkdays = signal<number[]>([]);

  totalSpecialties = computed(
    () => new Set(this.doctors().map((d) => d.specialty)).size,
  );

  avgInterval = computed(() => {
    const docs = this.doctors();
    return docs.length
      ? Math.round(
          docs.reduce((acc, d) => acc + d.appointmentInterval, 0) / docs.length,
        )
      : 0;
  });

  canSave = computed(() => {
    return (
      !this.errorHorarioGlobal() &&
      !this.errorFechas() &&
      Object.keys(this.errorHorariosDia()).length === 0 &&
      this.horariosValidos()
    );
  });

  ngOnInit(): void {
    this.loadDoctors();
  }

  // ── Carga inicial ─────────────────────────────────────────────────────────

  loadDoctors(): void {
    this.loading.set(true);
    this.errorCarga.set('');

    this.adminService.getDoctors().subscribe({
      next: (doctors) => {
        const scheduleRequests = doctors.map((doctor) =>
          this.adminService.getSchedulesByDoctor(doctor.id),
        );

        forkJoin(scheduleRequests).subscribe({
          next: (allSchedules) => {
            const doctorsConHorarios = doctors.map((doctor, i) => ({
              ...doctor,
              workdays: doctor.workdays ?? [],
              ...this.mapSchedulesToDoctor(allSchedules[i], doctor),
            }));
            this.doctors.set(doctorsConHorarios);
            this.loading.set(false);
          },
          error: () => {
            this.doctors.set(
              doctors.map((d) => ({ ...d, workdays: d.workdays ?? [] })),
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

  private mapSchedulesToDoctor(
    schedules: dtoSchedule[],
    doctor: Doctor,
  ): Partial<Doctor> {
    if (!schedules?.length) return {};

    const daySchedules: { [day: number]: DaySchedule } = {};
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
    const startTime = schedules[0].startTime.substring(0, 5);
    const endTime = schedules[0].endTime.substring(0, 5);

    return { startTime, endTime, daySchedules, workdays };
  }

  private workdayToNumber(workday: dtoSchedule['workday']): number | null {
    const map: { [key: string]: number } = {
      LUNES: 1,
      MARTES: 2,
      MIERCOLES: 3,
      JUEVES: 4,
      VIERNES: 5,
    };
    return map[workday] ?? null;
  }

  // ── Edición ───────────────────────────────────────────────────────────────

  edit(doctor: Doctor): void {
    if (doctor.status === false) return;
    this.editingId.set(doctor.id);
    this.editForm.set({
      ...doctor,
      workdays: [...(doctor.workdays ?? [])],
      daySchedules: { ...(doctor.daySchedules ?? {}) },
    });
    this.originalWorkdays.set([...(doctor.workdays ?? [])]);
    this.savedId.set(null);
    this.errorHorarioGlobal.set('');
    this.errorHorariosDia.set({});
    this.errorFechas.set('');
    this.showDaySchedules.set(false);
  }

  cancelEdit(): void {
    this.editingId.set(null);
    this.editForm.set(null);
    this.errorHorarioGlobal.set('');
    this.errorHorariosDia.set({});
    this.errorFechas.set('');
  }

  save(): void {
    const form = this.editForm();
    if (!form) return;
    if (!this.canSave()) return;

    const calls: any[] = [];
    const original = this.doctors().find((d) => d.id === form.id);

    if (original && original.appointmentInterval !== form.appointmentInterval) {
      calls.push(
        this.adminService.updateAppointmentInterval(
          form.id,
          form.appointmentInterval,
        ),
      );
    }

    if (original && original.laborStart !== form.laborStart) {
      calls.push(this.adminService.updateLaborStart(form.id, form.laborStart));
    }
    if (original && original.laborEnd !== form.laborEnd) {
      calls.push(this.adminService.updateLaborEnd(form.id, form.laborEnd));
    }

    (form.workdays ?? []).forEach((day) => {
      const workday = this.DAY_TO_WORKDAY[day];
      const ds = form.daySchedules?.[day];
      const startTime = ds?.startTime ?? form.startTime;
      const endTime = ds?.endTime ?? form.endTime;
      const esDayNuevo = !this.originalWorkdays().includes(day);

      if (esDayNuevo) {
        calls.push(
          this.adminService.createSchedule(
            form.id,
            workday,
            startTime,
            endTime,
          ),
        );
      } else {
        calls.push(
          this.adminService.updateSchedule(
            form.id,
            workday,
            startTime,
            endTime,
          ),
        );
      }
    });

    if (!calls.length) {
      this.cancelEdit();
      return;
    }

    forkJoin(calls).subscribe({
      next: () => {
        this.doctors.update((list) =>
          list.map((d) => (d.id === form.id ? form : d)),
        );
        this.savedId.set(form.id);
        this.editingId.set(null);
        this.editForm.set(null);
        this.errorHorarioGlobal.set('');
        this.errorHorariosDia.set({});
        this.errorFechas.set('');
        setTimeout(() => this.savedId.set(null), 3000);
      },
      error: () => {},
    });
  }

  // ── Toggle enabled/disabled ───────────────────────────────────────────────

  openToggleModal(doctor: Doctor): void {
    this.doctorToToggle.set(doctor);
    this.showConfirmModal.set(true);
  }

  closeToggleModal(): void {
    this.showConfirmModal.set(false);
    this.doctorToToggle.set(null);
  }

  confirmToggle(): void {
    const doctor = this.doctorToToggle();
    if (!doctor) return;

    if (!doctor.status) {
      this.adminService
        .enableDoctor(doctor.id, doctor.laborStart, doctor.laborEnd)
        .subscribe({
          next: () => {
            this.doctors.update((list) =>
              list.map((d) =>
                d.id === doctor.id ? { ...d, status: true } : d,
              ),
            );
            this.closeToggleModal();
          },
          error: (err) => {
            this.forceModalMessage.set(
              err?.error ?? 'Error al habilitar el médico.',
            );
            this.closeToggleModal();
            this.showForceModal.set(true);
          },
        });
      return;
    }

    this.adminService.disableDoctor(doctor.id, false).subscribe({
      next: () => {
        this.doctors.update((list) =>
          list.map((d) => (d.id === doctor.id ? { ...d, status: false } : d)),
        );
        this.closeToggleModal();
      },
      error: (err) => {
        this.forceModalMessage.set(
          err?.error ?? 'El médico tiene restricciones para ser deshabilitado.',
        );
        this.showConfirmModal.set(false);
        this.showForceModal.set(true);
      },
    });
  }

  confirmForceDisable(): void {
    const target = this.doctorToToggle();
    if (!target) return;

    this.adminService.disableDoctor(target.id, true).subscribe({
      next: () => {
        this.doctors.update((list) =>
          list.map((d) => (d.id === target.id ? { ...d, status: false } : d)),
        );
        this.showForceModal.set(false);
        this.forceModalMessage.set('');
        this.doctorToToggle.set(null);
      },
      error: (err) => {
        this.forceModalMessage.set(
          err?.error ?? 'Error al forzar la deshabilitación.',
        );
      },
    });
  }

  cancelForceDisable(): void {
    this.showForceModal.set(false);
    this.doctorToToggle.set(null);
    this.forceModalMessage.set('');
  }

  // ── Helpers de formulario ─────────────────────────────────────────────────

  horariosValidos(): boolean {
    const form = this.editForm();
    if (!form) return true;
    if (form.startTime >= form.endTime) return false;
    for (const day of form.workdays ?? []) {
      const ds = form.daySchedules?.[day];
      if (ds && ds.startTime >= ds.endTime) return false;
    }
    return true;
  }

  updateField(field: keyof Doctor, value: any): void {
    const form = this.editForm();
    if (!form) return;
    const updated = { ...form, [field]: value };
    this.editForm.set(updated);

    if (field === 'startTime' || field === 'endTime') {
      if (updated.startTime >= updated.endTime) {
        this.errorHorarioGlobal.set(
          'La hora de inicio no puede ser igual o posterior a la hora de fin.',
        );
      } else {
        this.errorHorarioGlobal.set('');
      }
    }

    if (field === 'laborStart' || field === 'laborEnd') {
      if (
        updated.laborStart &&
        updated.laborEnd &&
        updated.laborStart >= updated.laborEnd
      ) {
        this.errorFechas.set(
          'La fecha de inicio no puede ser igual o posterior a la fecha de fin.',
        );
      } else {
        this.errorFechas.set('');
      }
    }
  }

  toggleDay(day: number): void {
    const form = this.editForm();
    if (!form) return;
    const days = (form.workdays ?? []).includes(day)
      ? form.workdays.filter((d) => d !== day)
      : [...(form.workdays ?? []), day].sort((a, b) => a - b);
    const daySchedules = { ...(form.daySchedules ?? {}) };
    if (!days.includes(day)) delete daySchedules[day];
    this.editForm.set({ ...form, workdays: days, daySchedules });
  }

  updateDaySchedule(
    day: number,
    field: keyof DaySchedule,
    value: string,
  ): void {
    const form = this.editForm();
    if (!form) return;
    const daySchedules = { ...(form.daySchedules ?? {}) };
    daySchedules[day] = {
      startTime: daySchedules[day]?.startTime ?? form.startTime,
      endTime: daySchedules[day]?.endTime ?? form.endTime,
      [field]: value,
    };
    this.editForm.set({ ...form, daySchedules });

    const ds = daySchedules[day];
    const errors = { ...this.errorHorariosDia() };
    if (ds.startTime >= ds.endTime) {
      errors[day] =
        'La hora de inicio no puede ser igual o posterior a la hora de fin.';
    } else {
      delete errors[day];
    }
    this.errorHorariosDia.set(errors);
  }

  resetDaySchedule(day: number): void {
    const form = this.editForm();
    if (!form) return;
    const daySchedules = { ...(form.daySchedules ?? {}) };
    delete daySchedules[day];
    this.editForm.set({ ...form, daySchedules });
  }

  hasDayOverride(day: number): boolean {
    return !!this.editForm()?.daySchedules?.[day];
  }

  getDayScheduleValue(day: number, field: keyof DaySchedule): string {
    const form = this.editForm();
    if (!form) return '';
    return (
      form.daySchedules?.[day]?.[field] ??
      (field === 'startTime' ? form.startTime : form.endTime)
    );
  }

  getWorkDayLabels(workdays: number[]): string {
    if (!workdays?.length) return '';
    return this.DAY_LABELS.filter((_, i) => workdays.includes(i)).join(', ');
  }

  getDayScheduleKeys(doctor: Doctor): number[] {
    return Object.keys(doctor.daySchedules ?? {}).map(Number);
  }
  formatSpecialty(specialty: string): string {
    if (!specialty) return '';
    return specialty
      .replace(/[\[\]]/g, '') // quita corchetes si los hay
      .split(',') // separa si hay múltiples
      .map(
        (s) =>
          s
            .trim()
            .toLowerCase()
            .replace(/_/g, ' ') // reemplaza _ por espacio
            .replace(/\b\w/g, (c) => c.toUpperCase()), // primera letra mayúscula
      )
      .join(', ');
  }
  hasRealDayOverrides(doctor: Doctor): boolean {
    const keys = this.getDayScheduleKeys(doctor);
    if (!keys.length) return false;
    return keys.some((day) => {
      const ds = doctor.daySchedules![day];
      return ds.startTime !== doctor.startTime || ds.endTime !== doctor.endTime;
    });
  }
  getEditFormDayScheduleCount(): number {
    return Object.keys(this.editForm()?.daySchedules ?? {}).length;
  }
}
