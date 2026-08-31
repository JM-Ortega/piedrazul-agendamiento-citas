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
import {
  InputComponent,
  SanitizeRule,
} from '../../../../design-system/atoms/input/input.component';
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
import {
  DEFAULT_DOCUMENT_MAX_LENGTH,
  DOCUMENT_RULES,
  validateDocumentForType,
} from '../../../helpers/document-validation';

export type PatientFormData = Omit<Patient, 'id'>;

export const EMPTY_PATIENT_FORM: PatientFormData = {
  identificationType: '',
  identification: '',
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
  { value: 'OTRO', label: 'Otro' },
];

/**
 * Formulario completo de datos de un paciente: tipo de documento (y,
 * opcionalmente, número de documento vía `showDocumentNumber`), nombres,
 * apellidos, teléfono, sexo, fecha de nacimiento, correo y teléfono de
 * acudiente.
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
  /** Si es `true`, renderiza y valida también el número de documento. */
  @Input() showDocumentNumber = false;

  /** Emite el formulario completo cada vez que cambia cualquier campo. */
  @Output() valueChange = new EventEmitter<PatientFormData>();

  readonly sexOptions = SEX_OPTIONS;
  readonly nameMin = NAME_MIN_DEFAULT;
  readonly emailMax = EMAIL_MAX_DEFAULT;
  readonly documentMaxLength = DEFAULT_DOCUMENT_MAX_LENGTH;

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

  /** Regla de sanitización del número de documento según el tipo elegido. */
  documentSanitizeRule = computed<SanitizeRule>(() => {
    const type = this.documentTypeSignal();
    if (!type) return 'alphanumeric';
    return DOCUMENT_RULES[type]?.sanitize ?? 'alphanumeric';
  });

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

  /**
   * Actualiza un campo del formulario, notifica al `ControlValueAccessor`/padre,
   * y revalida en vivo cualquier error que ya estuviera mostrado para ese
   * campo (y los campos que dependen de él) para que desaparezca apenas el
   * valor deja de ser inválido.
   */
  set<K extends keyof PatientFormData>(field: K, val: string): void {
    this.value = { ...this.value, [field]: val as PatientFormData[K] };
    if (field === 'birthDate') this.birthDateSignal.set(val);
    if (field === 'identificationType') this.documentTypeSignal.set(val);

    this.revalidateIfTouched(field);
    if (field === 'identificationType') {
      this.revalidateIfTouched('identification');
      this.revalidateIfTouched('birthDate');
      this.revalidateIfTouched('guardianPhone');
    }
    if (field === 'birthDate') {
      this.revalidateIfTouched('guardianPhone');
    }

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
   * Valida todos los campos del formulario (el número de documento solo si
   * `showDocumentNumber` está activo) y actualiza los errores.
   *
   * @returns `true` si el formulario es válido en su totalidad.
   */
  validate(): boolean {
    const fields: (keyof PatientFormData)[] = [
      'identificationType',
      'firstName',
      'lastName',
      'phone',
      'sex',
      'birthDate',
      'email',
      'guardianPhone',
      ...(this.showDocumentNumber ? (['identification'] as const) : []),
    ];

    const e: Record<string, string> = {};
    for (const field of fields) {
      const message = this.validateField(field, this.value[field]);
      if (message) e[field as string] = message;
    }

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

  /**
   * Valida un único campo. Única fuente de verdad usada tanto por
   * {@link validate} (validación completa al continuar) como por
   * {@link revalidateIfTouched} (revalidación en vivo mientras se escribe).
   */
  private validateField(field: keyof PatientFormData, value: unknown): string {
    const str = String(value ?? '');
    switch (field) {
      case 'identificationType':
      case 'sex':
        return str ? '' : 'Este campo es obligatorio';
      case 'identification':
        if (!this.showDocumentNumber) return '';
        return this.validateDocumentNumber(str, this.value.identificationType);
      case 'firstName':
      case 'lastName':
        return validateName(str, { max: this.nameMax }) ?? '';
      case 'phone':
        return validatePhone(str, true) ?? '';
      case 'birthDate':
        return validateBirthDate(str, this.value.identificationType) ?? '';
      case 'email':
        return validateEmail(str, this.emailMax) ?? '';
      case 'guardianPhone':
        return (
          validateGuardianPhone(
            str,
            isMinorPatient(this.value.identificationType, this.value.birthDate)
          ) ?? ''
        );
      default:
        return '';
    }
  }

  private validateDocumentNumber(doc: string, type: string): string {
    const trimmed = doc.trim();
    if (!trimmed) return 'Este campo es obligatorio';
    if (!type) return '';
    return validateDocumentForType(type, trimmed) ?? '';
  }

  /**
   * Si el campo indicado ya tiene un error mostrado, lo recalcula y lo
   * actualiza (o lo limpia si ya es válido). No introduce errores nuevos
   * para campos que el usuario aún no ha intentado enviar.
   */
  private revalidateIfTouched(field: keyof PatientFormData): void {
    if (!(field in this.errors())) return;

    const message = this.validateField(field, this.value[field]);
    this.errors.update((current) => {
      const next = { ...current };
      if (message) {
        next[field as string] = message;
      } else {
        delete next[field as string];
      }
      return next;
    });
  }
}
