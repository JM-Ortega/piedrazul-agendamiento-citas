import {
  ChangeDetectionStrategy,
  Component,
  EventEmitter,
  Output,
  computed,
  forwardRef,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ControlValueAccessor, NG_VALUE_ACCESSOR } from '@angular/forms';
import { InputComponent } from '../../../../design-system/atoms/input/input.component';
import {
  SelectComponent,
  SelectOption,
} from '../../../../design-system/atoms/select/select.component';
import { DatepickerComponent } from '../../../../design-system/molecules/datepicker/datepicker.component';
import {
  parseLocalDateString,
  toIsoDateString,
} from '../../../helpers/transform-date-local';
import {
  isMinorByBirthDate,
  validateBirthDate,
  validateGuardianPhone,
} from '../../../helpers/patient-validation';

/**
 * Datos mínimos requeridos para completar el registro de paciente de alguien que ya tiene una
 * cuenta del sistema pero aún no está registrado como paciente.
 */
export interface ExistingUserFormData {
  sex: string;
  birthDate: string;
  guardianPhone: string;
}

export const EMPTY_GUARDIAN_FORM: ExistingUserFormData = {
  sex: '',
  birthDate: '',
  guardianPhone: '',
};

const SEX_OPTIONS: SelectOption[] = [
  { value: 'MASCULINO', label: 'Masculino' },
  { value: 'FEMENINO', label: 'Femenino' },
];

/**
 * Formulario de sexo, fecha de nacimiento y teléfono de acudiente. Se usa cuando la persona ya
 * tiene una cuenta del sistema y solo falta completar su registro como paciente.
 *
 * Implementa `ControlValueAccessor` para poder usarse con `[(ngModel)]`
 * desde el componente padre.
 */
@Component({
  selector: 'app-user-data-form',
  standalone: true,
  imports: [FormsModule, InputComponent, SelectComponent, DatepickerComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [
    {
      provide: NG_VALUE_ACCESSOR,
      useExisting: forwardRef(() => ExistingUserFormComponent),
      multi: true,
    },
  ],
  templateUrl: './existing-user-form.component.html',
})
export class ExistingUserFormComponent implements ControlValueAccessor {
  /** Fecha máxima seleccionable en el datepicker de fecha de nacimiento (por defecto, hoy). */
  maxBirthDate = new Date();

  /** Emite el formulario completo cada vez que cambia cualquier campo. */
  @Output() valueChange = new EventEmitter<ExistingUserFormData>();

  readonly sexOptions = SEX_OPTIONS;

  value: ExistingUserFormData = { ...EMPTY_GUARDIAN_FORM };

  /** Errores de validación por campo, mostrados en cada input del formulario. */
  errors = signal<Record<string, string>>({});

  // eslint-disable-next-line @typescript-eslint/no-unused-vars, @typescript-eslint/no-empty-function
  private onChange = (_: ExistingUserFormData) => {};
  // eslint-disable-next-line @typescript-eslint/no-empty-function
  private onTouched = () => {};

  private birthDateSignal = signal('');

  /** true si, según la fecha de nacimiento ingresada, la persona es menor de edad. */
  isMinor = computed(() => isMinorByBirthDate(this.birthDateSignal()));

  birthDateAsDate = computed<Date | null>(() => {
    const raw = this.birthDateSignal();
    return raw ? parseLocalDateString(raw) : null;
  });

  /** Lee el valor actual de un campo del formulario. */
  get<K extends keyof ExistingUserFormData>(field: K): string {
    return this.value[field] ?? '';
  }

  /** Actualiza un campo del formulario y notifica al `ControlValueAccessor` y al padre. */
  set<K extends keyof ExistingUserFormData>(field: K, val: string): void {
    this.value = { ...this.value, [field]: val };
    if (field === 'birthDate') this.birthDateSignal.set(val);
    this.onChange(this.value);
    this.valueChange.emit(this.value);
  }

  /** Igual que {@link set}, pero acepta el tipo genérico que emiten los inputs del design system. */
  setFromInput<K extends keyof ExistingUserFormData>(
    field: K,
    val: string | number | boolean | null
  ): void {
    this.set(field, String(val ?? ''));
  }

  onBirthDateChange(date: Date | null): void {
    const formatted = date ? toIsoDateString(date) : '';
    this.set('birthDate', formatted);
  }

  /**
   * Valida sexo (obligatorio), fecha de nacimiento (obligatoria, no
   * futura) y teléfono de acudiente (obligatorio solo si es menor de
   * edad) y actualiza los errores.
   *
   * @returns `true` si el formulario es válido en su totalidad.
   */
  validate(): boolean {
    const e: Record<string, string> = {};
    const f = this.value;

    if (!f.sex) e['sex'] = 'Este campo es obligatorio';

    const birth = validateBirthDate(f.birthDate);
    if (birth) e['birthDate'] = birth;

    const gPhone = validateGuardianPhone(
      f.guardianPhone,
      isMinorByBirthDate(f.birthDate)
    );
    if (gPhone) e['guardianPhone'] = gPhone;

    this.errors.set(e);
    return Object.keys(e).length === 0;
  }

  /** Limpia todos los errores mostrados (usado al reiniciar el formulario desde el padre). */
  clearErrors(): void {
    this.errors.set({});
  }

  writeValue(value: ExistingUserFormData | null): void {
    this.value = value ? { ...value } : { ...EMPTY_GUARDIAN_FORM };
    this.birthDateSignal.set(this.value.birthDate ?? '');
  }

  registerOnChange(fn: (value: ExistingUserFormData) => void): void {
    this.onChange = fn;
  }

  registerOnTouched(fn: () => void): void {
    this.onTouched = fn;
  }
}
