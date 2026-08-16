import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  OnInit,
  output,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  LucideCalendar,
  LucideChevronDown,
  LucideChevronUp,
  LucideClock,
  LucideSave,
} from '@lucide/angular';
import { ButtonComponent } from '../../../../design-system/atoms/button/button.component';
import { InputComponent } from '../../../../design-system/atoms/input/input.component';
import { SelectComponent } from '../../../../design-system/atoms/select/select.component';
import { DatepickerComponent } from '../../../../design-system/molecules/datepicker/datepicker.component';
import { toIsoDateString } from '../../../../shared/helpers/transform-date-local';
import { DaySchedule } from '../../../../shared/models/interfaces/daySchedule.model';
import { Doctor } from '../../../../shared/models/interfaces/doctor.model';
import { ToSelectOptionsPipe } from '../../../../shared/pipes/ToSelectOptionsPipe';
import {
  DoctorFormValidationService,
  FormErrors,
} from '../../service/doctor-form-validation.service';

export interface DoctorSaveEvent {
  form: Doctor;
  originalDoctor: Doctor;
  originalWorkdays: number[];
  removedWorkdays: number[];
}

@Component({
  selector: 'app-doctor-edit-form',
  templateUrl: './doctor-edit-form.component.html',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    FormsModule,
    LucideCalendar,
    LucideChevronDown,
    LucideChevronUp,
    LucideClock,
    LucideSave,
    ButtonComponent,
    DatepickerComponent,
    ToSelectOptionsPipe,
    SelectComponent,
    InputComponent,
  ],
})
export class DoctorEditFormComponent implements OnInit {
  private validationService = inject(DoctorFormValidationService);

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

  readonly timeOptions: string[] = (() => {
    const opts: string[] = [];
    for (let h = 7; h <= 12; h++) {
      for (let m = 0; m < 60; m += 5) {
        if (h === 12 && m > 0) break;
        opts.push(
          `${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}`
        );
      }
    }
    return opts;
  })();

  // ── Inputs / Outputs ──────────────────────────────────────────────────────
  doctor = input.required<Doctor>();
  saved = output<DoctorSaveEvent>();
  cancelled = output<void>();

  // ── Internal state ────────────────────────────────────────────────────────
  editForm = signal<Doctor | null>(null);
  showDaySchedules = signal(false);
  errors = signal<FormErrors>({
    horarioGlobal: '',
    fechas: '',
    fechaInicio: '',
    fechaFin: '',
    intervalo: '',
    dias: '',
    horariosDia: {},
  });

  private originalDoctor = signal<Doctor | null>(null);
  private originalWorkdays = signal<number[]>([]);
  // ── Helpers ────────────────────────────────────────────────────────
  toDateString(date: Date | null): string {
    return date ? toIsoDateString(date) : '';
  }
  // ── Computed ──────────────────────────────────────────────────────────────
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
    const fDays = [...(form.workdays ?? [])].sort((a, b) => a - b);
    const oDays = [...(orig.workdays ?? [])].sort((a, b) => a - b);
    if (JSON.stringify(fDays) !== JSON.stringify(oDays)) return true;
    for (const day of fDays) {
      const fd = form.daySchedules?.[day];
      const od = orig.daySchedules?.[day];
      if (fd?.startTime !== od?.startTime || fd?.endTime !== od?.endTime)
        return true;
    }
    return false;
  });

  canSave = computed(() => {
    const errs = this.errors();
    const form = this.editForm();
    if (!form) return false;
    return (
      this.hasChanges() &&
      !errs.horarioGlobal &&
      !errs.fechas &&
      !errs.fechaInicio &&
      !errs.fechaFin &&
      !errs.intervalo &&
      !errs.dias &&
      Object.keys(errs.horariosDia).length === 0 &&
      this.validationService.horariosValidos(form)
    );
  });

  // ── Lifecycle ─────────────────────────────────────────────────────────────
  ngOnInit(): void {
    const doc = this.doctor();
    const clone: Doctor = {
      ...doc,
      workdays: [...(doc.workdays ?? [])],
      daySchedules: { ...(doc.daySchedules ?? {}) },
    };
    this.editForm.set(clone);
    this.originalDoctor.set(clone);
    this.originalWorkdays.set([...(doc.workdays ?? [])]);
    this.errors.set(this.validationService.validateForm(clone));
  }

  // ── Form methods ──────────────────────────────────────────────────────────
  updateField(field: keyof Doctor, value: Doctor[keyof Doctor]): void {
    const form = this.editForm();
    if (!form) return;
    const updated = { ...form, [field]: value };
    this.editForm.set(updated);
    this.errors.set(this.validationService.validateForm(updated));
  }

  toggleDay(day: number): void {
    const form = this.editForm();
    if (!form) return;
    const days = (form.workdays ?? []).includes(day)
      ? form.workdays.filter((d) => d !== day)
      : [...(form.workdays ?? []), day].sort((a, b) => a - b);
    const daySchedules = { ...(form.daySchedules ?? {}) };
    if (!days.includes(day)) delete daySchedules[day];
    const updated = { ...form, workdays: days, daySchedules };
    this.editForm.set(updated);
    this.errors.set(this.validationService.validateForm(updated));
  }

  updateDaySchedule(
    day: number,
    field: keyof DaySchedule,
    value: string
  ): void {
    const form = this.editForm();
    if (!form) return;
    const daySchedules = { ...(form.daySchedules ?? {}) };
    daySchedules[day] = {
      startTime: daySchedules[day]?.startTime ?? form.startTime,
      endTime: daySchedules[day]?.endTime ?? form.endTime,
      [field]: value,
    };
    const updated = { ...form, daySchedules };
    this.editForm.set(updated);
    this.errors.set(this.validationService.validateForm(updated));
  }

  resetDaySchedule(day: number): void {
    const form = this.editForm();
    if (!form) return;
    const daySchedules = { ...(form.daySchedules ?? {}) };
    delete daySchedules[day];
    const updated = { ...form, daySchedules };
    this.editForm.set(updated);
    this.errors.set(this.validationService.validateForm(updated));
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

  getEditFormDayScheduleCount(): number {
    return Object.keys(this.editForm()?.daySchedules ?? {}).length;
  }

  onSave(): void {
    if (!this.canSave()) return;
    const form = this.editForm();
    const orig = this.originalDoctor();
    if (!form || !orig) return;

    const removedWorkdays = this.originalWorkdays().filter(
      (day) => !form.workdays.includes(day)
    );

    this.saved.emit({
      form,
      originalDoctor: orig,
      originalWorkdays: this.originalWorkdays(),
      removedWorkdays, // ← añadir
    });
  }
  onLaborStartInput(event: Event): void {
    const input = event.target as HTMLInputElement;
    const value = input.value;
    if (value) {
      const parts = value.split('-');
      if (parts[0].length > 4) {
        parts[0] = parts[0].slice(0, 4);
        input.value = parts.join('-');
        this.updateField('laborStart', input.value);
      }
    }
  }

  onLaborEndInput(event: Event): void {
    const input = event.target as HTMLInputElement;
    const value = input.value;
    if (value) {
      const parts = value.split('-');
      if (parts[0].length > 4) {
        parts[0] = parts[0].slice(0, 4);
        input.value = parts.join('-');
        this.updateField('laborEnd', input.value);
      }
    }
  }
}
