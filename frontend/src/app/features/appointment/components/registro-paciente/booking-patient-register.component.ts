import {
  ChangeDetectionStrategy,
  Component,
  inject,
  OnInit,
  output,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { LucideArrowLeft, LucideCalendar } from '@lucide/angular';
import { PatientService } from '../../../../core/services/patient.service';
import { ButtonComponent } from '../../../../design-system/atoms/button/button.component';
import { Patient } from '../../../../shared/models/interfaces/patient.model';
import { FormatoPipe } from '../../../../shared/pipes/formatoPipe';
import { BookingStateService } from '../../services/booking-state.service';

// Tipo utilitario: extrae las keys de T cuyo valor es string
// (ignora undefined en props opcionales, y excluye uniones de literales como 'CEDULA' | 'PASAPORTE')
type KeysMatching<T, V> = {
  [K in keyof T]-?: [V] extends [NonNullable<T[K]>]
    ? NonNullable<T[K]> extends V
      ? K
      : never
    : never;
}[keyof T];

/**
 * Capturar y validar los datos de un paciente que no fue encontrado en el sistema para
 * que pueda ser registrado al confirmar la cita.
 */
@Component({
  selector: 'app-booking-patient-register',
  standalone: true,
  imports: [
    FormsModule,
    LucideArrowLeft,
    LucideCalendar,
    MatDatepickerModule,
    FormatoPipe,
    ButtonComponent,
    InputComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './booking-patient-register.component.html',
})
export class BookingPatientRegisterComponent implements OnInit {
  protected state = inject(BookingStateService);
  protected patientService = inject(PatientService);

  ngOnInit(): void {
    this.patientService.loadDocumentTypes();
  }

  advance = output<void>();
  goBack = output<void>();

  docTypeError = signal(false);
  firstNameError = signal(false);
  lastNameError = signal(false);
  phoneError = signal(false);
  genderError = signal(false);
  birthDateError = signal(false);
  emailError = signal(false);
  guardianPhoneError = signal(false);
  firstNameErrorMsg = signal('');
  lastNameErrorMsg = signal('');
  phoneErrorMsg = signal('');
  emailErrorMsg = signal('');
  guardianPhoneErrorMsg = signal('');
  birthDateErrorMsg = signal('Ingrese una fecha de nacimiento válida');

  readonly maxBirthDate = new Date();
  readonly NAME_MAX = 30;
  readonly NAME_MIN = 2;
  readonly EMAIL_MAX = 50;
  readonly PHONE_MAX = 10;

  private readonly VALID_NAME_REGEX = /^[a-zA-Z\s]+$/;
  private readonly VALID_EMAIL_REGEX =
    /^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$/;
  private readonly INVALID_EMAIL_CHARS = /['"<>()[\]\\,;:{}|^~`!#$%&*=?/]/;

  getPatientField<K extends keyof Omit<Patient, 'id'>>(
    key: K
  ): Omit<Patient, 'id'>[K] {
    return this.state.patientForm()[key];
  }

  setPatientField<K extends keyof Omit<Patient, 'id'>>(
    key: K,
    value: Omit<Patient, 'id'>[K]
  ): void {
    this.state.patientForm.update((f) => ({ ...f, [key]: value }));
  }

  /**
   * Setter para campos de tipo string, evita el problema de TypeScript
   * al inferir Omit<Patient, 'id'>[K] cuando K es una unión de keys.
   */
  private setStringField<K extends KeysMatching<Omit<Patient, 'id'>, string>>(
    key: K,
    value: string
  ): void {
    this.setPatientField(key, value as Omit<Patient, 'id'>[K]);
  }

  onTextFieldChange(
    field: Parameters<typeof this.setPatientField>[0],
    value: string | number | boolean | null
  ): void {
    this.setPatientField(field, String(value ?? ''));
  }

  onContinue(): void {
    if (!this.validateForm()) return;
    this.advance.emit();
  }

  onGoBack(): void {
    this.clearAllErrors();
    this.goBack.emit();
  }

  private validateForm(): boolean {
    const f = this.state.patientForm();

    const docTypeOk = this.validateDocType(f.documentType);
    const genderOk = this.validateGender(f.gender);
    const fnOk = this.validateNameField('firstName', f.firstName);
    const lnOk = this.validateNameField('lastName', f.lastName);
    const phoneOk = this.validatePhoneField(f.phone);
    const birthOk = this.validateBirthDate(f.birthDate, f.documentType);
    const emailOk = this.validateEmailField(f.email);
    const gPhoneOk = this.validateGuardianPhone(f);

    return (
      docTypeOk &&
      genderOk &&
      fnOk &&
      lnOk &&
      phoneOk &&
      birthOk &&
      emailOk &&
      gPhoneOk
    );
  }

  private validateDocType(value: string | undefined): boolean {
    const ok = !!value;
    this.docTypeError.set(!ok);
    return ok;
  }

  private validateGender(value: string | undefined): boolean {
    const ok = !!value;
    this.genderError.set(!ok);
    return ok;
  }

  private validateNameField(
    field: 'firstName' | 'lastName',
    value: string | undefined
  ): boolean {
    const errorSig =
      field === 'firstName' ? this.firstNameError : this.lastNameError;
    const msgSig =
      field === 'firstName' ? this.firstNameErrorMsg : this.lastNameErrorMsg;
    const trimmed = value?.trim() ?? '';

    if (!trimmed) {
      errorSig.set(true);
      msgSig.set('Este campo es obligatorio');
      return false;
    }
    if (trimmed.length < this.NAME_MIN) {
      errorSig.set(true);
      msgSig.set(`Debe ingresar al menos ${this.NAME_MIN} caracteres`);
      return false;
    }
    if (!this.VALID_NAME_REGEX.test(trimmed)) {
      errorSig.set(true);
      msgSig.set('Solo se permiten letras, espacios y guión medio (-)');
      return false;
    }
    errorSig.set(false);
    msgSig.set('');
    return true;
  }

  private validatePhoneField(value: string | undefined): boolean {
    const trimmed = value?.trim() ?? '';

    if (!trimmed) {
      this.phoneError.set(true);
      this.phoneErrorMsg.set('Este campo es obligatorio');
      return false;
    }
    if (!/^[0-9]{10}$/.test(trimmed)) {
      this.phoneError.set(true);
      this.phoneErrorMsg.set(
        'Ingrese un número válido de exactamente 10 dígitos'
      );
      return false;
    }
    this.phoneError.set(false);
    this.phoneErrorMsg.set('');
    return true;
  }

  private validateBirthDate(
    value: string | undefined,
    documentType: string | undefined
  ): boolean {
    if (!value) {
      this.birthDateError.set(true);
      this.birthDateErrorMsg.set('Ingrese una fecha de nacimiento válida');
      return false;
    }

    const today = new Date();
    today.setHours(0, 0, 0, 0);
    const input = new Date(value);
    input.setHours(0, 0, 0, 0);

    if (input >= today) {
      this.birthDateError.set(true);
      this.birthDateErrorMsg.set(
        'La fecha de nacimiento debe ser anterior a hoy'
      );
      return false;
    }

    if (documentType === 'CEDULA') {
      const age = this.calcAge(input);
      if (age < 18) {
        this.birthDateError.set(true);
        this.birthDateErrorMsg.set(
          'La fecha ingresada indica que el paciente es menor de edad. ' +
            'Para Cedula el paciente debe tener 18 años o más.'
        );
        return false;
      }
    }

    if (
      documentType === 'TARJETA_IDENTIDAD' ||
      documentType === 'REGISTRO_NACIMIENTO'
    ) {
      const age = this.calcAge(input);
      if (age > 18) {
        this.birthDateError.set(true);
        this.birthDateErrorMsg.set(
          'La fecha ingresada indica que el paciente es mayor de edad. ' +
            'El tipo de documento seleccionado no es válido.'
        );
        return false;
      }
    }

    this.birthDateError.set(false);
    this.birthDateErrorMsg.set('');
    return true;
  }

  private calcAge(birthDate: Date): number {
    const today = new Date();
    let age = today.getFullYear() - birthDate.getFullYear();
    const m = today.getMonth() - birthDate.getMonth();
    if (m < 0 || (m === 0 && today.getDate() < birthDate.getDate())) age--;
    return age;
  }

  private validateEmailField(value: string | undefined): boolean {
    if (!value) {
      this.emailError.set(false);
      this.emailErrorMsg.set('');
      return true;
    }

    if (this.INVALID_EMAIL_CHARS.test(value)) {
      this.emailError.set(true);
      this.emailErrorMsg.set(
        'No se permiten caracteres especiales como \', ", <, >, (, ), [, ], etc.'
      );
      return false;
    }

    if (!this.VALID_EMAIL_REGEX.test(value)) {
      this.emailError.set(true);
      this.emailErrorMsg.set(
        'La estructura del correo no es válida. Ejemplo: nombre@dominio.com'
      );
      return false;
    }

    this.emailError.set(false);
    this.emailErrorMsg.set('');
    return true;
  }

  private validateGuardianPhone(f: Omit<Patient, 'id'>): boolean {
    if (f.guardianPhone) {
      if (!/^[0-9]{10}$/.test(f.guardianPhone)) {
        this.guardianPhoneError.set(true);
        this.guardianPhoneErrorMsg.set(
          `Ingrese un número válido de ${this.PHONE_MAX} dígitos`
        );
        return false;
      }
      this.guardianPhoneError.set(false);
      this.guardianPhoneErrorMsg.set('');
    }

    if (!f.birthDate) return true;
    const age = this.calcAge(new Date(f.birthDate));

    if (age < 18 && !f.guardianPhone) {
      this.guardianPhoneError.set(true);
      this.guardianPhoneErrorMsg.set(
        'El celular del acudiente es obligatorio para menores de 18 años'
      );
      return false;
    }

    if (age >= 18) {
      this.guardianPhoneError.set(false);
      this.guardianPhoneErrorMsg.set('');
    }
    return true;
  }

  private clearAllErrors(): void {
    this.docTypeError.set(false);
    this.firstNameError.set(false);
    this.lastNameError.set(false);
    this.phoneError.set(false);
    this.genderError.set(false);
    this.birthDateError.set(false);
    this.emailError.set(false);
    this.guardianPhoneError.set(false);
    this.firstNameErrorMsg.set('');
    this.lastNameErrorMsg.set('');
    this.phoneErrorMsg.set('');
    this.emailErrorMsg.set('');
    this.guardianPhoneErrorMsg.set('');
    this.birthDateErrorMsg.set('Ingrese una fecha de nacimiento válida');
  }

  onBirthDateChange(value: Date | string): void {
    const formatted =
      value instanceof Date ? this.state.formatLocalDate(value) : value;
    this.setStringField('birthDate', formatted);
  }
}
