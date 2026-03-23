import { Component, computed, signal } from '@angular/core';
import {
  Calendar,
  CheckCircle,
  ChevronDown,
  ChevronUp,
  Clock,
  Edit3,
  LucideAngularModule,
  Save,
  Settings,
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
  readonly weekDays = [0, 1, 2, 3, 4, 5, 6];

  // ── Datos quemados directamente ───────────────────────
  doctors = signal<Doctor[]>([
    {
      id: 'd1',
      name: 'Dr. Carlos Ramírez',
      specialty: 'Terapia Neural',
      interval: 20,
      workDays: [1, 2, 3, 4, 5],
      startTime: '08:00',
      endTime: '16:00',
      windowWeeks: 4,
      email: 'carlos@piedrazul.com',
    },
    {
      id: 'd2',
      name: 'Dra. María López',
      specialty: 'Quiropraxia',
      interval: 30,
      workDays: [1, 2, 3, 4, 5],
      startTime: '09:00',
      endTime: '17:00',
      windowWeeks: 3,
      email: 'maria@piedrazul.com',
    },
    {
      id: 'd3',
      name: 'Dr. Andrés Torres',
      specialty: 'Quiropraxia',
      interval: 45,
      workDays: [1, 2, 3, 4, 5, 6],
      startTime: '07:00',
      endTime: '15:00',
      windowWeeks: 2,
      email: 'andres@piedrazul.com',
    },
  ]);

  editingId = signal<string | null>(null);
  editForm = signal<Doctor | null>(null);
  savedId = signal<string | null>(null);
  showDaySchedules = signal(false);

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
    this.showDaySchedules.set(false);
  }

  cancelEdit(): void {
    this.editingId.set(null);
    this.editForm.set(null);
  }

  save(): void {
    const form = this.editForm();
    if (!form) return;
    this.doctors.update((list) =>
      list.map((d) => (d.id === form.id ? form : d)),
    );
    this.savedId.set(form.id);
    this.editingId.set(null);
    this.editForm.set(null);
    setTimeout(() => this.savedId.set(null), 3000);
  }

  updateField(field: keyof Doctor, value: any): void {
    const form = this.editForm();
    if (!form) return;
    this.editForm.set({ ...form, [field]: value });
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
}
