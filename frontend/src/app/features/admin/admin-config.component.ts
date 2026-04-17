import { Component, computed, inject, OnInit, signal } from '@angular/core';
import {
  Calendar,
  CheckCircle,
  ChevronDown,
  ChevronUp,
  Clock,
  LucideAngularModule,
  Pencil,
  Power,
  PowerOff,
  Save,
  Settings,
  X,
} from 'lucide-angular';
import { forkJoin } from 'rxjs';
import { DaySchedule } from '../../models/interfaces/daySchedule.model';
import { Doctor } from '../../models/interfaces/doctor.model';
import { dtoSchedule } from '../../models/dtos/schedule.dto';
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
  readonly Pencil = Pencil;
  readonly Save = Save;
  readonly Clock = Clock;
  readonly Calendar = Calendar;
  readonly CheckCircle = CheckCircle;
  readonly ChevronDown = ChevronDown;
  readonly ChevronUp = ChevronUp;
  readonly Power = Power;
  readonly PowerOff = PowerOff;
  readonly X = X;

  readonly timeOptions: string[] = (() => {
    const options: string[] = [];
    for (let h = 5; h <= 12; h++) {
      for (let m = 0; m < 60; m += 5) {
        if (h === 12 && m > 0) break;
        const hh = h.toString().padStart(2, '0');
        const mm = m.toString().padStart(2, '0');
        options.push(`${hh}:${mm}`);
      }
    }
    return options;
  })();

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
  errorFechaInicio = signal('');
  errorFechaFin = signal('');
  errorIntervalo = signal('');
  errorDias = signal('');

  // ── Nuevos signals para error de guardado ─────────────────────────────────
  errorGuardado = signal('');
  showErrorGuardadoModal = signal(false);

  private originalWorkdays = signal<number[]>([]);
  private originalDoctor = signal<Doctor | null>(null);

  hasChanges = computed(() => {
    const form = this.editForm();
    const orig = this.originalDoctor();
    if (!form || !orig) return false;

    if (
      form.appointmentInterval !== orig.appointmentInterval ||
      form.laborStart !== orig.laborStart ||
      form.laborEnd !== orig.laborEnd ||
      form.startTime !== orig.startTime ||
      form.endTime !== orig.endTime
    )
      return true;

    const formDays = [...(form.workdays ?? [])].sort((a, b) => a - b);
    const origDays = [...(orig.workdays ?? [])].sort((a, b) => a - b);
    if (JSON.stringify(formDays) !== JSON.stringify(origDays)) return true;

    for (const day of formDays) {
      const fd = form.daySchedules?.[day];
      const od = orig.daySchedules?.[day];
      if (fd?.startTime !== od?.startTime || fd?.endTime !== od?.endTime)
        return true;
    }

    return false;
  });

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
      this.hasChanges() &&
      !this.errorHorarioGlobal() &&
      !this.errorFechas() &&
      !this.errorFechaInicio() &&
      !this.errorFechaFin() &&
      !this.errorIntervalo() &&
      !this.errorDias() &&
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

  private toTimeBackend(time: string | undefined): string {
    if (!time) return '';
    return time.length === 5 ? `${time}:00` : time;
  }

  private timeToMinutes(time: string): number {
    if (!time) return 0;
    const [h, m] = time.split(':').map(Number);
    return h * 60 + m;
  }

  private validateFranjaVsIntervalo(
    startTime: string,
    endTime: string,
    interval: number,
  ): string {
    if (!startTime || !endTime || startTime >= endTime) return '';
    const duracion =
      this.timeToMinutes(endTime) - this.timeToMinutes(startTime);
    if (duracion < interval) {
      return `La franja horaria (${duracion} min) no puede ser menor al intervalo (${interval} min).`;
    }
    return '';
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
    this.editingId.set(doctor.id);
    this.editForm.set({
      ...doctor,
      workdays: [...(doctor.workdays ?? [])],
      daySchedules: { ...(doctor.daySchedules ?? {}) },
    });
    this.originalWorkdays.set([...(doctor.workdays ?? [])]);
    this.originalDoctor.set({
      ...doctor,
      workdays: [...(doctor.workdays ?? [])],
      daySchedules: { ...(doctor.daySchedules ?? {}) },
    });
    this.savedId.set(null);
    this.showDaySchedules.set(false);
    this.validateForm(); // ← debe ser lo ÚLTIMO, sin ningún .set('') después
  }
  cancelEdit(): void {
    this.editingId.set(null);
    this.editForm.set(null);
    this.originalDoctor.set(null);
    this.errorHorarioGlobal.set('');
    this.errorHorariosDia.set({});
    this.errorFechas.set('');
    this.errorFechaInicio.set('');
    this.errorFechaFin.set('');
    this.errorIntervalo.set('');
    this.errorDias.set('');
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
      const startTime = this.toTimeBackend(
        ds?.startTime ?? form.startTime ?? '05:00',
      );
      const endTime = this.toTimeBackend(
        ds?.endTime ?? form.endTime ?? '12:00',
      );
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
        this.reloadDoctor(form.id, form); // ← reemplaza el update manual
        this.savedId.set(form.id);
        this.editingId.set(null);
        this.editForm.set(null);
        this.errorHorarioGlobal.set('');
        this.errorHorariosDia.set({});
        this.errorFechas.set('');
        setTimeout(() => this.savedId.set(null), 3000);
      },
      error: (err) => {
        const raw: string =
          err?.error?.detail ??
          'Error al guardar los cambios. Intente de nuevo.';

        const detail = raw.startsWith('User is already active')
          ? 'El médico ya está trabajando activamente. Debe deshabilitarlo primero para poder cambiar su período laboral.'
          : raw;

        this.errorGuardado.set(detail);
        this.showErrorGuardadoModal.set(true);
      },
    });
  }
  private reloadDoctor(doctorId: string, fallback: Doctor): void {
    forkJoin([
      this.adminService.getDoctors(), // ← datos frescos del doctor (status incluido)
      this.adminService.getSchedulesByDoctor(doctorId), // ← horarios frescos
    ]).subscribe({
      next: ([doctors, schedules]) => {
        const freshDoctor = doctors.find((d) => d.id === doctorId) ?? fallback;
        const mapped = this.mapSchedulesToDoctor(schedules, freshDoctor);
        const updated: Doctor = { ...freshDoctor, ...mapped };
        this.doctors.update((list) =>
          list.map((d) => (d.id === doctorId ? updated : d)),
        );
      },
      error: () => {
        this.doctors.update((list) =>
          list.map((d) => (d.id === doctorId ? fallback : d)),
        );
      },
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
              err?.error?.detail ?? 'Error al habilitar el médico.',
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
          err?.error?.detail ??
            'El médico tiene restricciones para ser deshabilitado.',
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
          err?.error?.detail ?? 'Error al forzar la deshabilitación.',
        );
      },
    });
  }

  cancelForceDisable(): void {
    this.showForceModal.set(false);
    this.doctorToToggle.set(null);
    this.forceModalMessage.set('');
  }

  closeErrorGuardadoModal(): void {
    this.showErrorGuardadoModal.set(false);
    this.errorGuardado.set('');
  }

  // ── Helpers de formulario ─────────────────────────────────────────────────

  horariosValidos(): boolean {
    const form = this.editForm();
    if (!form) return true;
    if (form.startTime >= form.endTime) return false;
    const duracion =
      this.timeToMinutes(form.endTime) - this.timeToMinutes(form.startTime);
    if (duracion < form.appointmentInterval) return false;
    for (const day of form.workdays ?? []) {
      const ds = form.daySchedules?.[day];
      if (ds) {
        if (ds.startTime >= ds.endTime) return false;
        const dur =
          this.timeToMinutes(ds.endTime) - this.timeToMinutes(ds.startTime);
        if (dur < form.appointmentInterval) return false;
      }
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
        const error = this.validateFranjaVsIntervalo(
          updated.startTime,
          updated.endTime,
          updated.appointmentInterval,
        );
        this.errorHorarioGlobal.set(error);
      }
    }

    if (field === 'appointmentInterval') {
      if (!value || value <= 0) {
        this.errorIntervalo.set('El intervalo debe ser mayor a 0.');
      } else {
        this.errorIntervalo.set('');
        if (
          updated.startTime &&
          updated.endTime &&
          updated.startTime < updated.endTime
        ) {
          const error = this.validateFranjaVsIntervalo(
            updated.startTime,
            updated.endTime,
            value,
          );
          this.errorHorarioGlobal.set(error);
        }
        const errors = { ...this.errorHorariosDia() };
        for (const day of updated.workdays ?? []) {
          const ds = updated.daySchedules?.[day];
          if (ds && ds.startTime < ds.endTime) {
            const err = this.validateFranjaVsIntervalo(
              ds.startTime,
              ds.endTime,
              value,
            );
            if (err) {
              errors[day] = err;
            } else {
              delete errors[day];
            }
          }
        }
        this.errorHorariosDia.set(errors);
      }
    }

    if (field === 'laborStart') {
      if (!value) {
        this.errorFechaInicio.set('La fecha de inicio es obligatoria.');
      } else {
        this.errorFechaInicio.set('');
      }
    }

    if (field === 'laborEnd') {
      if (!value) {
        this.errorFechaFin.set('La fecha de fin es obligatoria.');
      } else {
        this.errorFechaFin.set('');
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

    if (!days.length) {
      this.errorDias.set('Debe seleccionar al menos un día de atención.');
    } else {
      this.errorDias.set('');
    }
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
      const error = this.validateFranjaVsIntervalo(
        ds.startTime,
        ds.endTime,
        form.appointmentInterval,
      );
      if (error) {
        errors[day] = error;
      } else {
        delete errors[day];
      }
    }

    this.errorHorariosDia.set(errors);
  }

  resetDaySchedule(day: number): void {
    const form = this.editForm();
    if (!form) return;
    const daySchedules = { ...(form.daySchedules ?? {}) };
    delete daySchedules[day];
    this.editForm.set({ ...form, daySchedules });
    const errors = { ...this.errorHorariosDia() };
    delete errors[day];
    this.errorHorariosDia.set(errors);
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
      .replace(/[\[\]]/g, '')
      .split(',')
      .map((s) =>
        s
          .trim()
          .toLowerCase()
          .replace(/_/g, ' ')
          .replace(/\b\w/g, (c) => c.toUpperCase()),
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
  private validateForm(): void {
    const form = this.editForm();
    if (!form) return;

    // Horario global
    if (!form.startTime || !form.endTime) {
      this.errorHorarioGlobal.set(
        'La hora de inicio y hora de fin son obligatorias.',
      );
    } else if (form.startTime >= form.endTime) {
      this.errorHorarioGlobal.set(
        'La hora de inicio no puede ser igual o posterior a la hora de fin.',
      );
    } else {
      this.errorHorarioGlobal.set(
        this.validateFranjaVsIntervalo(
          form.startTime,
          form.endTime,
          form.appointmentInterval,
        ),
      );
    }

    // Intervalo
    if (!form.appointmentInterval || form.appointmentInterval <= 0) {
      this.errorIntervalo.set('El intervalo debe ser mayor a 0.');
    } else {
      this.errorIntervalo.set('');
    }

    // Fechas laborales
    if (!form.laborStart) {
      this.errorFechaInicio.set('La fecha de inicio es obligatoria.');
    } else {
      this.errorFechaInicio.set('');
    }

    if (!form.laborEnd) {
      this.errorFechaFin.set('La fecha de fin es obligatoria.');
    } else {
      this.errorFechaFin.set('');
    }

    if (form.laborStart && form.laborEnd && form.laborStart >= form.laborEnd) {
      this.errorFechas.set(
        'La fecha de inicio no puede ser igual o posterior a la fecha de fin.',
      );
    } else {
      this.errorFechas.set('');
    }

    // Días
    if (!(form.workdays ?? []).length) {
      this.errorDias.set('Debe seleccionar al menos un día de atención.');
    } else {
      this.errorDias.set('');
    }

    // Horarios por día
    const errors: { [day: number]: string } = {};
    for (const day of form.workdays ?? []) {
      const ds = form.daySchedules?.[day];
      if (!ds) continue;
      if (ds.startTime >= ds.endTime) {
        errors[day] =
          'La hora de inicio no puede ser igual o posterior a la hora de fin.';
      } else {
        const err = this.validateFranjaVsIntervalo(
          ds.startTime,
          ds.endTime,
          form.appointmentInterval,
        );
        if (err) errors[day] = err;
      }
    }
    this.errorHorariosDia.set(errors);
  }
  getEditFormDayScheduleCount(): number {
    return Object.keys(this.editForm()?.daySchedules ?? {}).length;
  }
}
