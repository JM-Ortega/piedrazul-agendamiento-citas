import {
  ChangeDetectionStrategy,
  Component,
  EventEmitter,
  Input,
  Output,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  LucideCalendarRange,
  LucideChevronDown,
  LucideChevronUp,
  LucideCircleAlert,
  LucideCircleCheck,
  LucideClock,
  LucideDynamicIcon,
  LucideInfo,
  LucideStethoscope,
  type LucideIcon,
} from '@lucide/angular';

import { ButtonComponent } from '../../../../design-system/atoms/button/button.component';
import { InputComponent } from '../../../../design-system/atoms/input/input.component';
import { SelectComponent } from '../../../../design-system/atoms/select/select.component';
import { DatepickerComponent } from '../../../../design-system/molecules/datepicker/datepicker.component';
import { toggleInArray } from '../../../../shared/helpers/array-utils';
import { toIsoDateString } from '../../../../shared/helpers/transform-date-local';
import { FormatoPipe } from '../../../../shared/pipes/formatoPipe';
import { ToSelectOptionsPipe } from '../../../../shared/pipes/ToSelectOptionsPipe';
import { DoctorFormData } from '../../models/interfaces/DoctorFormData';
export interface SpecialtyOption {
  name: string;
  icon: LucideIcon;
  colorClass: string;
}

/**
 * Sub-formulario de datos de doctor (especialidad, horario, período laboral)
 * usado dentro de la creación de usuario.
 */
@Component({
  selector: 'app-create-user-doctor-form',
  templateUrl: './create-user-doctor-form.component.html',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    FormsModule,
    LucideCalendarRange,
    LucideCircleAlert,
    LucideInfo,
    LucideStethoscope,
    LucideDynamicIcon,
    LucideClock,
    FormatoPipe,
    InputComponent,
    ButtonComponent,
    SelectComponent,
    ToSelectOptionsPipe,
    DatepickerComponent,
    LucideChevronDown,
    LucideChevronUp,
    LucideCircleCheck,
  ],
})
export class CreateUserDoctorFormComponent {
  // ── Inputs ────────────────────────────────────────────────────────────────
  @Input() data!: DoctorFormData;
  @Input() specialtyOptions: SpecialtyOption[] = [];
  @Input() loadingSpecialties = false;
  @Input() timeOptions: string[] = [];
  @Input() maxInterval = 300;
  @Input() daysOfWeek: { value: number; label: string }[] = [];
  @Input() errors: Partial<Record<string, string>> = {};
  @Input() loadError: string | null = null;

  // ── Outputs ───────────────────────────────────────────────────────────────
  @Output() dataChange = new EventEmitter<Partial<DoctorFormData>>();
  @Output() fieldBlurred = new EventEmitter<string>();

  // ── Estado privado ────────────────────────────────────────────────────────

  /** Controla si el panel de horario está expandido manualmente. */
  private scheduleOpened = false;
  // ── Getters ───────────────────────────────────────────────────────────────

  /** El panel de horario se ve abierto si el usuario lo abrió o si hay errores dentro. */
  get isScheduleOpen(): boolean {
    return this.scheduleOpened || this.hasScheduleErrors;
  }

  /** True si algún campo del bloque de horario/período laboral tiene datos. */
  get hasScheduleData(): boolean {
    const d = this.data;
    return !!(
      d.startTime ||
      d.endTime ||
      d.interval ||
      (d.workDays && d.workDays.length > 0) ||
      d.bookingWindowWeeks
    );
  }

  /** True si algún campo del bloque de horario/período laboral tiene error. */
  get hasScheduleErrors(): boolean {
    return !!(
      this.errors['startTime'] ||
      this.errors['endTime'] ||
      this.errors['interval'] ||
      this.errors['workDays'] ||
      this.errors['bookingWindowWeeks']
    );
  }

  /** Valor actual de bookingWindowWeeks, o null si no está seteado. */
  get bookingWindowWeeksValue(): number | null {
    return this.data.bookingWindowWeeks || null;
  }

  /** Valor actual del intervalo entre citas, o null si no está seteado. */
  get intervalValue(): number | null {
    return this.data.interval ?? null;
  }

  // ── Panel colapsable de horario ──────────────────────────────────────────
  /** Alterna la apertura manual del panel de horario. */
  toggleSchedule(): void {
    this.scheduleOpened = !this.scheduleOpened;
  }

  // ── Especialidades y Perido Laboral ───────────────────────────────────────

  /** True si la especialidad dada ya está seleccionada. */
  isSpecialtySelected(name: string): boolean {
    return this.data.specialty.includes(name);
  }

  /** Agrega o quita una especialidad de la selección y emite el cambio. */
  toggleSpecialty(name: string): void {
    this.dataChange.emit({
      specialty: toggleInArray(this.data.specialty, name),
    });
  }

  /** True si el día dado ya está seleccionado. */
  isWorkDaySelected(day: number): boolean {
    return this.data.workDays.includes(day);
  }

  /** Agrega o quita un día de trabajo de la selección y emite el cambio. */
  toggleWorkDay(day: number): void {
    this.dataChange.emit({ workDays: toggleInArray(this.data.workDays, day) });
  }

  /**
   * Convierte el Date del datepicker a 'yyyy-mm-dd', lo emite y dispara
   * la validación de ambos campos (inicio/fin) porque son interdependientes.
   */
  onDateChange(field: 'laborStart' | 'laborEnd', date: Date | null): void {
    this.emit(field, date ? toIsoDateString(date) : '');
    this.fieldBlurred.emit(field);
    this.fieldBlurred.emit(field === 'laborStart' ? 'laborEnd' : 'laborStart');
  }

  /** Emite el cambio de bookingWindowWeeks, convirtiendo a número o null. */
  onBookingWindowWeeksChange(value: string | number | boolean | null): void {
    this.emit('bookingWindowWeeks', value === null ? null : Number(value));
    this.fieldBlurred.emit('bookingWindowWeeks');
  }

  /** Emite el cambio de intervalo, convirtiendo a número o null. */
  onIntervalChange(value: string | number | boolean | null): void {
    this.emit(
      'interval',
      value === null || value === '' ? null : Number(value)
    );
    this.fieldBlurred.emit('interval');
  }
  // ── Emit genérico ─────────────────────────────────────────────────────────
  /** Emite un parche de un solo campo hacia el componente padre. */
  emit(field: keyof DoctorFormData, value: unknown): void {
    this.dataChange.emit({ [field]: value });
  }
}
