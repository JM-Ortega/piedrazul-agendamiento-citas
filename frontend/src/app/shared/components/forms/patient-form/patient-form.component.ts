import {
  Component,
  EventEmitter,
  Input,
  Output,
  computed,
  forwardRef,
  inject,
  OnInit,
  signal,
  ChangeDetectionStrategy,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ControlValueAccessor, NG_VALUE_ACCESSOR } from '@angular/forms';
import { PatientService } from '../../../../core/services/patient.service';
import { Patient } from '../../../models/interfaces/patient.model';
import { FormatoPipe } from '../../../pipes/formatoPipe';
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
  EMAIL_MAX_DEFAULT,
  NAME_MAX_DEFAULT,
  NAME_MIN_DEFAULT,
  isMinorPatient,
  validateBirthDate,
  validateEmail,
  validateGuardianPhone,
  validateName,
  validatePhone,
} from '../../../helpers/patient-validation';

export type PatientFormData = Omit<Patient, 'id' | 'identification'>;

export const EMPTY_PATIENT_FORM: PatientFormData = {
  identificationType: '',
  firstName: '',
  lastName: '',
  phone: '',
  sex: '',
  birthDate: '',
  guardianPhone: '',
  email: '',
};

const SEX_OPTIONS: SelectOption[] = [
  { value: 'MASCULINO', label: 'Masculino' },
  { value: 'FEMENINO', label: 'Femenino' },
];

/**
 * Formulario completo de datos de un paciente nuevo: tipo y número de documento,
 * nombres, apellidos, teléfono, sexo, fecha de nacimiento, correo y teléfono de acudiente.
 */
@Component({
  selector: 'app-patient-data-form',
  standalone: true,
  imports: [FormsModule, InputComponent, SelectComponent, DatepickerComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [
    {
      provide: NG_VALUE_ACCESSOR,
      useExisting: forwardRef(() => PatientFormComponent),
      multi: true,
    },
  ],
  templateUrl: './patient-form.component.html',
})
export class PatientFormComponent implements ControlValueAccessor, OnInit {
  protected patientService = inject(PatientService);
  private formatoPipe = new FormatoPipe();

  /** Fecha máxima seleccionable en el datepicker de fecha de nacimiento (por defecto, hoy). */
  @Input() maxBirthDate = new Date();
  /** Longitud máxima permitida para nombres y apellidos. */
  @Input() nameMax = NAME_MAX_DEFAULT;

  /** Emite el formulario completo cada vez que cambia cualquier campo. */
  @Output() valueChange = new EventEmitter<PatientFormData>();

  readonly sexOptions = SEX_OPTIONS;
  readonly nameMin = NAME_MIN_DEFAULT;
  readonly emailMax = EMAIL_MAX_DEFAULT;

  value: PatientFormData = { ...EMPTY_PATIENT_FORM };
  /** Errores de validación por campo, mostrados en cada input del formulario. */
  errors = signal<Record<string, string>>({});

  // eslint-disable-next-line @typescript-eslint/no-unused-vars, @typescript-eslint/no-empty-function
  private onChange = (_: PatientFormData) => {};
  // eslint-disable-next-line @typescript-eslint/no-empty-function
  private onTouched = () => {};

  private birthDateSignal = signal('');
  private documentTypeSignal = signal('');

  documentTypeOptions = computed<SelectOption[]>(() =>
    this.patientService.documentTypes().map((type) => ({
      value: type,
      label: this.formatoPipe.transform(type),
    }))
  );

  /**
   * true si, según el tipo de documento o la fecha de nacimiento ingresada,
   * el paciente es menor de edad. Controla si el teléfono de acudiente es obligatorio.
   */
  isMinor = computed(() =>
    isMinorPatient(this.documentTypeSignal(), this.birthDateSignal())
  );

  ngOnInit(): void {
    this.patientService.loadDocumentTypes();
  }

  /** Lee el valor actual de un campo del formulario. */
  get<K extends keyof PatientFormData>(field: K): string {
    return (this.value[field] as string) ?? '';
  }

  /** Actualiza un campo del formulario y notifica al `ControlValueAccessor` y al padre. */
  set<K extends keyof PatientFormData>(field: K, val: string): void {
    this.value = { ...this.value, [field]: val as PatientFormData[K] };
    if (field === 'birthDate') this.birthDateSignal.set(val);
    if (field === 'identificationType') this.documentTypeSignal.set(val);
    this.onChange(this.value);
    this.valueChange.emit(this.value);
  }

  /** Igual que {@link set}, pero acepta el tipo genérico que emiten los inputs del design system. */
  setFromInput<K extends keyof PatientFormData>(
    field: K,
    val: string | number | boolean | null
  ): void {
    this.set(field, String(val ?? ''));
  }

  onBirthDateChange(date: Date | null): void {
    const formatted = date ? toIsoDateString(date) : '';
    this.set('birthDate', formatted);
  }

  birthDateAsDate = computed<Date | null>(() => {
    const raw = this.birthDateSignal();
    return raw ? parseLocalDateString(raw) : null;
  });

  /**
   * Valida todos los campos del formulario y actualiza los errores.
   *
   * @returns `true` si el formulario es válido en su totalidad.
   */
  validate(): boolean {
    const e: Record<string, string> = {};
    const f = this.value;

    if (!f.identificationType)
      e['identificationType'] = 'Este campo es obligatorio';
    if (!f.sex) e['sex'] = 'Este campo es obligatorio';

    const fn = validateName(f.firstName, { max: this.nameMax });
    if (fn) e['firstName'] = fn;

    const ln = validateName(f.lastName, { max: this.nameMax });
    if (ln) e['lastName'] = ln;

    const phone = validatePhone(f.phone, true);
    if (phone) e['phone'] = phone;

    const birth = validateBirthDate(f.birthDate, f.identificationType);
    if (birth) e['birthDate'] = birth;

    const email = validateEmail(f.email ?? '', this.emailMax);
    if (email) e['email'] = email;

    const gPhone = validateGuardianPhone(
      f.guardianPhone ?? '',
      isMinorPatient(f.identificationType, f.birthDate)
    );
    if (gPhone) e['guardianPhone'] = gPhone;

    this.errors.set(e);
    return Object.keys(e).length === 0;
  }

  /** Limpia todos los errores mostrados (usado al reiniciar el formulario desde el padre). */
  clearErrors(): void {
    this.errors.set({});
  }

  writeValue(value: PatientFormData | null): void {
    this.value = value ? { ...value } : { ...EMPTY_PATIENT_FORM };
    this.birthDateSignal.set(this.value.birthDate ?? '');
    this.documentTypeSignal.set(this.value.identificationType ?? '');
  }

  registerOnChange(fn: (value: PatientFormData) => void): void {
    this.onChange = fn;
  }

  registerOnTouched(fn: () => void): void {
    this.onTouched = fn;
  }
}
