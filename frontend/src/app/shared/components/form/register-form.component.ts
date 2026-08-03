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
import { PatientService } from '../../../core/services/patient.service';
import { Patient } from '../../models/interfaces/patient.model';
import { FormatoPipe } from '../../../shared/pipes/formatoPipe';
import { InputComponent } from '../../../design-system/atoms/input/input.component';
import {
  SelectComponent,
  SelectOption,
} from '../../../design-system/atoms/select/select.component';
import { DatepickerComponent } from '../../../design-system/molecules/datepicker/datepicker.component';
import {
  parseLocalDateString,
  toIsoDateString,
} from '../../../shared/helpers/transform-date-local';

export type PatientFormData = Omit<Patient, 'id' | 'documentNumber'>;

export const EMPTY_PATIENT_FORM: PatientFormData = {
  documentType: '',
  firstName: '',
  lastName: '',
  phone: '',
  gender: '',
  birthDate: '',
  guardianPhone: '',
  email: '',
};

const GENDER_OPTIONS: SelectOption[] = [
  { value: 'MASCULINO', label: 'Masculino' },
  { value: 'FEMENINO', label: 'Femenino' },
  { value: 'OTRO', label: 'Otro' },
];

const MINOR_DOCUMENT_TYPES = new Set([
  'TARJETA_IDENTIDAD',
  'REGISTRO_NACIMIENTO',
]);

const PHONE_LENGTH = 10;
const NAME_MIN = 2;
const EMAIL_MAX = 50;
const VALID_NAME_REGEX = /^[a-zA-Z\s]+$/;
const VALID_EMAIL_REGEX = /^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$/;
const INVALID_EMAIL_CHARS = /['"<>()[\]\\,;:{}|^~`!#$%&*=?/]/;

@Component({
  selector: 'app-patient-data-form',
  standalone: true,
  imports: [FormsModule, InputComponent, SelectComponent, DatepickerComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [
    {
      provide: NG_VALUE_ACCESSOR,
      useExisting: forwardRef(() => PatientDataFormComponent),
      multi: true,
    },
  ],
  templateUrl: './register-form.component.html',
})
export class PatientDataFormComponent implements ControlValueAccessor, OnInit {
  protected patientService = inject(PatientService);
  private formatoPipe = new FormatoPipe();

  @Input() maxBirthDate = new Date();
  @Input() nameMax = 30;

  @Output() valueChange = new EventEmitter<PatientFormData>();

  readonly genderOptions = GENDER_OPTIONS;
  readonly nameMin = NAME_MIN;
  readonly emailMax = EMAIL_MAX;

  value: PatientFormData = { ...EMPTY_PATIENT_FORM };
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

  isMinor = computed(() => {
    const byDocType = MINOR_DOCUMENT_TYPES.has(this.documentTypeSignal());
    const byBirthDate = this.isMinorByBirthDate(this.birthDateSignal());
    return byDocType || byBirthDate;
  });

  ngOnInit(): void {
    this.patientService.loadDocumentTypes();
  }

  get<K extends keyof PatientFormData>(field: K): string {
    return (this.value[field] as string) ?? '';
  }

  set<K extends keyof PatientFormData>(field: K, val: string): void {
    this.value = { ...this.value, [field]: val as PatientFormData[K] };
    if (field === 'birthDate') this.birthDateSignal.set(val);
    if (field === 'documentType') this.documentTypeSignal.set(val);
    this.onChange(this.value);
    this.valueChange.emit(this.value);
  }

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

  validate(): boolean {
    const e: Record<string, string> = {};
    const f = this.value;

    if (!f.documentType) e['documentType'] = 'Este campo es obligatorio';
    if (!f.gender) e['gender'] = 'Este campo es obligatorio';

    const fn = this.validateName(f.firstName);
    if (fn) e['firstName'] = fn;

    const ln = this.validateName(f.lastName);
    if (ln) e['lastName'] = ln;

    const phone = this.validatePhone(f.phone, true);
    if (phone) e['phone'] = phone;

    const birth = this.validateBirthDate(f.birthDate, f.documentType);
    if (birth) e['birthDate'] = birth;

    const email = this.validateEmail(f.email ?? '');
    if (email) e['email'] = email;

    const gPhone = this.validateGuardianPhone(
      f.guardianPhone ?? '',
      f.birthDate,
      f.documentType
    );
    if (gPhone) e['guardianPhone'] = gPhone;

    this.errors.set(e);
    return Object.keys(e).length === 0;
  }

  clearErrors(): void {
    this.errors.set({});
  }

  private validateName(value: string): string {
    const trimmed = value?.trim() ?? '';
    if (!trimmed) return 'Este campo es obligatorio';
    if (trimmed.length < NAME_MIN)
      return `Debe ingresar al menos ${NAME_MIN} caracteres`;
    if (trimmed.length > this.nameMax)
      return `Se permiten ingresar máximo ${this.nameMax} caracteres`;
    if (!VALID_NAME_REGEX.test(trimmed))
      return 'Solo se permiten letras y espacios';
    return '';
  }

  private validatePhone(value: string, required: boolean): string {
    const trimmed = value?.trim() ?? '';
    if (!trimmed) return required ? 'Este campo es obligatorio' : '';
    if (!new RegExp(`^[0-9]{${PHONE_LENGTH}}$`).test(trimmed)) {
      return `Ingrese un número válido de exactamente ${PHONE_LENGTH} dígitos`;
    }
    return '';
  }

  private validateBirthDate(value: string, documentType: string): string {
    if (!value) return 'Ingrese una fecha de nacimiento válida';

    const today = new Date();
    today.setHours(0, 0, 0, 0);
    const input = parseLocalDateString(value);
    input.setHours(0, 0, 0, 0);

    if (input >= today) return 'La fecha de nacimiento debe ser anterior a hoy';

    if (documentType === 'CEDULA') {
      const age = this.calcAge(input);
      if (age < 18) {
        return 'La fecha ingresada indica que el paciente es menor de edad. Para Cédula el paciente debe tener 18 años o más.';
      }
    }

    if (MINOR_DOCUMENT_TYPES.has(documentType)) {
      const age = this.calcAge(input);
      if (age >= 18) {
        return 'La fecha ingresada indica que el paciente es mayor de edad. El tipo de documento seleccionado no es válido.';
      }
    }

    return '';
  }

  private validateEmail(value: string): string {
    if (!value) return '';
    if (value.length > EMAIL_MAX)
      return `El correo no puede superar los ${EMAIL_MAX} caracteres`;
    if (INVALID_EMAIL_CHARS.test(value))
      return 'No se permiten caracteres especiales como \', ", <, >, (, ), [, ], etc.';
    if (!VALID_EMAIL_REGEX.test(value))
      return 'La estructura del correo no es válida. Ejemplo: nombre@dominio.com';
    return '';
  }

  private validateGuardianPhone(
    value: string,
    birthDate: string,
    documentType: string
  ): string {
    const trimmed = value?.trim() ?? '';
    if (trimmed) {
      const formatErr = this.validatePhone(trimmed, false);
      if (formatErr) return formatErr;
    }

    const isMinorPatient =
      MINOR_DOCUMENT_TYPES.has(documentType) ||
      (birthDate ? this.isMinorByBirthDate(birthDate) : false);

    if (isMinorPatient && !trimmed) {
      return 'El celular del acudiente es obligatorio para menores de 18 años';
    }
    return '';
  }

  private calcAge(birthDate: Date): number {
    const today = new Date();
    let age = today.getFullYear() - birthDate.getFullYear();
    const m = today.getMonth() - birthDate.getMonth();
    if (m < 0 || (m === 0 && today.getDate() < birthDate.getDate())) age--;
    return age;
  }

  private isMinorByBirthDate(value: string): boolean {
    if (!value) return false;
    return this.calcAge(parseLocalDateString(value)) < 18;
  }

  writeValue(value: PatientFormData | null): void {
    this.value = value ? { ...value } : { ...EMPTY_PATIENT_FORM };
    this.birthDateSignal.set(this.value.birthDate ?? '');
    this.documentTypeSignal.set(this.value.documentType ?? '');
  }

  registerOnChange(fn: (value: PatientFormData) => void): void {
    this.onChange = fn;
  }

  registerOnTouched(fn: () => void): void {
    this.onTouched = fn;
  }
}
