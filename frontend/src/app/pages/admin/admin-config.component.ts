import { Component, computed, signal } from '@angular/core';
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
import { DaySchedule } from '../../models/daySchedule.model';
import { Doctor } from '../../models/doctor.model';

@Component({
  selector: 'app-admin-config',
  templateUrl: './admin-config.component.html',
  standalone: true,
  imports: [LucideAngularModule],
})
export class AdminConfigComponent {
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

  doctors = signal<Doctor[]>([
    {
      id: 'd1',
      name: 'Dr. Carlos Ramírez',
      specialty: 'Terapia Neural',
      interval: 20,
      workDays: [1, 2, 3, 4, 5],
      startTime: '08:00',
      endTime: '16:00',
      email: 'carlos@piedrazul.com',
      enabled: true,
    },
    {
      id: 'd2',
      name: 'Dra. María López',
      specialty: 'Quiropraxia',
      interval: 30,
      workDays: [1, 2, 3, 4, 5],
      startTime: '09:00',
      endTime: '17:00',
      email: 'maria@piedrazul.com',
      enabled: true,
    },
    {
      id: 'd3',
      name: 'Dr. Andrés Torres',
      specialty: 'Quiropraxia',
      interval: 45,
      workDays: [1, 2, 3, 4, 5],
      startTime: '07:00',
      endTime: '15:00',
      email: 'andres@piedrazul.com',
      enabled: true,
    },
  ]);

  editingId = signal<string | null>(null);
  editForm = signal<Doctor | null>(null);
  savedId = signal<string | null>(null);
  showDaySchedules = signal(false);
  showConfirmModal = signal(false);
  doctorToToggle = signal<Doctor | null>(null);
  errorHorarioGlobal = signal('');
  errorHorariosDia = signal<{ [day: number]: string }>({});
  totalSpecialties = computed(
    () => new Set(this.doctors().map((d) => d.specialty)).size,
  );

  avgInterval = computed(() => {
    const docs = this.doctors();
    return docs.length
      ? Math.round(docs.reduce((acc, d) => acc + d.interval, 0) / docs.length)
      : 0;
  });

  edit(doctor: Doctor): void {
    this.editingId.set(doctor.id);
    this.editForm.set({
      ...doctor,
      daySchedules: { ...(doctor.daySchedules ?? {}) },
    });
    this.savedId.set(null);
    this.errorHorarioGlobal.set('');
    this.errorHorariosDia.set({});
    this.showDaySchedules.set(false);
  }

  cancelEdit(): void {
    this.editingId.set(null);
    this.editForm.set(null);
    this.errorHorarioGlobal.set('');
    this.errorHorariosDia.set({});
  }

  save(): void {
    const form = this.editForm();
    if (!form) return;

    if (
      this.errorHorarioGlobal() ||
      Object.keys(this.errorHorariosDia()).length > 0
    )
      return;
    if (!this.horariosValidos()) return;

    this.doctors.update((list) =>
      list.map((d) => (d.id === form.id ? form : d)),
    );
    this.savedId.set(form.id);
    this.editingId.set(null);
    this.editForm.set(null);
    this.errorHorarioGlobal.set('');
    this.errorHorariosDia.set({});
    setTimeout(() => this.savedId.set(null), 3000);
  }

  horariosValidos(): boolean {
    const form = this.editForm();
    if (!form) return true;

    if (form.startTime >= form.endTime) return false;

    for (const day of form.workDays) {
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
  }
  toggleDay(day: number): void {
    const form = this.editForm();
    if (!form) return;
    const days = form.workDays.includes(day)
      ? form.workDays.filter((d) => d !== day)
      : [...form.workDays, day].sort((a, b) => a - b);
    const daySchedules = { ...(form.daySchedules ?? {}) };
    if (!days.includes(day)) delete daySchedules[day];
    this.editForm.set({ ...form, workDays: days, daySchedules });
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

  getWorkDayLabels(workDays: number[]): string {
    return this.DAY_LABELS.filter((_, i) => workDays.includes(i)).join(', ');
  }

  getDayScheduleKeys(doctor: Doctor): number[] {
    return Object.keys(doctor.daySchedules ?? {}).map(Number);
  }

  getEditFormDayScheduleCount(): number {
    return Object.keys(this.editForm()?.daySchedules ?? {}).length;
  }

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
    this.doctors.update((list) =>
      list.map((d) =>
        d.id === doctor.id
          ? { ...d, enabled: d.enabled === false ? true : false }
          : d,
      ),
    );
    this.closeToggleModal();
  }
}
