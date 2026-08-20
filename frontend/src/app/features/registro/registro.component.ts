import { CommonModule } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  OnInit,
  signal,
  ViewChild,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import {
  LucideArrowLeft,
  LucideCheckCircle,
  LucideDynamicIcon,
  LucideEye,
  LucideEyeOff,
  LucideKeyRound,
  LucideSearch,
  LucideUserPlus,
} from '@lucide/angular';
import Keycloak from 'keycloak-js';
import { ButtonComponent } from '../..//design-system/atoms/button/button.component';
import { InputComponent } from '../../design-system/atoms/input/input.component';
import {
  SelectComponent,
  SelectOption,
} from '../../design-system/atoms/select/select.component';
import { DatepickerComponent } from '../../design-system/molecules/datepicker/datepicker.component';
import {
  PatientPublicResponse,
  PatientService,
} from '../../core/services/patient.service';
import {
  PatientDataFormComponent,
  PatientFormData,
  EMPTY_PATIENT_FORM,
} from '../../shared/components/form/register-form.component';
import { FormatoPipe } from '../../shared/pipes/formatoPipe';
import { SanitizeRule } from '../../design-system/atoms/input/input.component';
import {
  DOCUMENT_RULES,
  DEFAULT_DOCUMENT_MAX_LENGTH,
  validateDocumentForType,
} from '../../shared/helpers/document-validation';
import { AppError } from '../../shared/models/interfaces/api-error.model';
import {
  parseLocalDateString,
  toIsoDateString,
} from '../../shared/helpers/transform-date-local';

type RegistroStep = 1 | 2 | 3;
type PatientStatus =
  | 'idle'
  | 'not-found'
  | 'found'
  | 'existing-user'
  | 'needs-patient-role'
  | 'already-linked'
  | 'inconsistent';

/** Datos mínimos requeridos cuando la persona ya tiene cuenta de usuario
 *  pero aún no está registrada como paciente. */
interface ExistingUserRegistrationData {
  sex: string;
  birthDate: string;
  guardianPhone: string;
}

const EMPTY_EXISTING_USER_DATA: ExistingUserRegistrationData = {
  sex: '',
  birthDate: '',
  guardianPhone: '',
};

const SEX_OPTIONS: SelectOption[] = [
  { value: 'MASCULINO', label: 'Masculino' },
  { value: 'FEMENINO', label: 'Femenino' },
];

@Component({
  selector: 'app-registro',
  standalone: true,
  imports: [
    LucideArrowLeft,
    LucideCheckCircle,
    LucideKeyRound,
    LucideSearch,
    LucideUserPlus,
    LucideDynamicIcon,
    CommonModule,
    FormsModule,
    ButtonComponent,
    InputComponent,
    SelectComponent,
    DatepickerComponent,
    PatientDataFormComponent,
    FormatoPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './registro.component.html',
})
export class RegistroComponent implements OnInit {
  ngOnInit(): void {
    this.patientService.loadDocumentTypes();
  }
  protected patientService = inject(PatientService);
  private keycloak = inject(Keycloak);
  private router = inject(Router);

  @ViewChild('patientForm') patientFormRef?: PatientDataFormComponent;

  readonly Eye = LucideEye;
  readonly EyeOff = LucideEyeOff;
  readonly sexOptions = SEX_OPTIONS;

  step = signal<RegistroStep>(1);
  isLoading = signal(false);
  errorMessage = signal('');
  successMessage = signal('');
  showPassword = signal(false);
  showConfirmPassword = signal(false);

  documentNumber = signal('');
  patientStatus = signal<PatientStatus>('idle');
  foundPatient = signal<PatientPublicResponse | null>(null);

  form = signal<PatientFormData>({ ...EMPTY_PATIENT_FORM });

  /** Datos mínimos para el caso 'existing-user' (sexo, fecha de nacimiento, acudiente). */
  existingUserData = signal<ExistingUserRegistrationData>({
    ...EMPTY_EXISTING_USER_DATA,
  });

  password = signal('');
  confirmPassword = signal('');
  verificationCode = signal('');

  protected readonly PASSWORD_MIN = 6;
  protected readonly PASSWORD_MAX = 100;
  private readonly PASSWORD_ALPHANUMERIC = /^(?=.*[a-zA-Z])(?=.*[0-9]).+$/;
  readonly maxBirthDate = new Date();

  errors = signal<Record<string, string>>({});

  protected readonly DOCUMENT_INPUT_MAX_LENGTH = 20;

  readonly isNewPatient = computed(() => this.patientStatus() === 'not-found');
  readonly isExistingPatient = computed(() => this.patientStatus() === 'found');
  readonly isExistingSystemUser = computed(
    () => this.patientStatus() === 'existing-user'
  );
  /** Caso 3: paciente y cuenta ya existen, solo falta el rol de paciente. */
  readonly needsPatientRoleOnly = computed(
    () => this.patientStatus() === 'needs-patient-role'
  );
  /** Caso 4: ya está completamente registrado, no debe continuar. */
  readonly isFullyRegistered = computed(
    () => this.patientStatus() === 'already-linked'
  );
  /** Estado que no encaja en ninguno de los casos válidos. */
  readonly isInconsistentState = computed(
    () => this.patientStatus() === 'inconsistent'
  );

  readonly requiresPassword = computed(
    () => this.isNewPatient() || this.isExistingPatient()
  );
  /** Casos que necesitan validar un código OTP (todos menos el paciente nuevo real). */
  readonly requiresOtp = computed(
    () =>
      this.isExistingPatient() ||
      this.isExistingSystemUser() ||
      this.needsPatientRoleOnly()
  );

  readonly displayName = computed(() => {
    const p = this.foundPatient();

    if (p?.firstName || p?.lastName) {
      return `${p.firstName ?? ''} ${p.lastName ?? ''}`.trim();
    }

    const f = this.form();
    if (f.firstName || f.lastName) {
      return `${f.firstName} ${f.lastName}`.trim();
    }

    return '';
  });

  documentSanitizeRule = computed<SanitizeRule>(() => {
    const type = this.form().identificationType;
    if (!type) return 'alphanumeric';
    return DOCUMENT_RULES[type]?.sanitize ?? 'alphanumeric';
  });

  documentMaxLengthDynamic = computed<number>(() => {
    const type = this.form().identificationType;
    return DOCUMENT_RULES[type]?.max ?? DEFAULT_DOCUMENT_MAX_LENGTH;
  });

  /** Fecha de nacimiento del bloque 'existing-user' como Date, para el datepicker. */
  existingUserBirthDateAsDate = computed<Date | null>(() => {
    const raw = this.existingUserData().birthDate;
    return raw ? parseLocalDateString(raw) : null;
  });

  /** true si, según la fecha de nacimiento ingresada, la persona es menor de edad. */
  isMinorExistingUser = computed(() => {
    const raw = this.existingUserData().birthDate;
    if (!raw) return false;
    return this.calcAge(parseLocalDateString(raw)) < 18;
  });

  onFormChange(value: PatientFormData): void {
    this.form.set(value);
    this.revalidateDocumentNumber();
  }

  private revalidateDocumentNumber(): void {
    const type = this.form().identificationType;
    if (!type) return;
    const msg = validateDocumentForType(type, this.documentNumber());
    this.errors.update((e) => {
      const next = { ...e };
      if (msg) next['documentNumber'] = msg;
      else delete next['documentNumber'];
      return next;
    });
  }

  onDocumentNumberChange(value: string | number | boolean | null): void {
    this.onDocumentChange(String(value ?? ''));
    this.revalidateDocumentNumber();
  }

  onPasswordChange(value: string | number | boolean | null): void {
    this.password.set(String(value ?? ''));
  }

  onConfirmPasswordChange(value: string | number | boolean | null): void {
    this.confirmPassword.set(String(value ?? ''));
  }

  onVerificationCodeChange(value: string | number | boolean | null): void {
    this.verificationCode.set(String(value ?? ''));
  }

  onExistingUserSexChange(value: string): void {
    this.existingUserData.update((d) => ({ ...d, sex: value }));
  }

  onExistingUserBirthDateChange(date: Date | null): void {
    this.existingUserData.update((d) => ({
      ...d,
      birthDate: date ? toIsoDateString(date) : '',
    }));
  }

  onExistingUserGuardianPhoneChange(
    value: string | number | boolean | null
  ): void {
    this.existingUserData.update((d) => ({
      ...d,
      guardianPhone: String(value ?? ''),
    }));
  }

  searchPatient(): void {
    this.patientStatus.set('idle');
    this.foundPatient.set(null);
    this.errorMessage.set('');
    this.successMessage.set('');
    this.errors.set({});

    const doc = this.documentNumber().trim();

    if (!/^[a-zA-Z0-9]{1,20}$/.test(doc)) {
      this.errors.set({
        documentNumber:
          'Ingresa un documento válido (máximo 20 caracteres alfanuméricos, sin caracteres especiales)',
      });
      return;
    }

    this.isLoading.set(true);

    this.patientService.getPublicByDocument(doc).subscribe({
      next: (patient) => {
        this.isLoading.set(false);
        this.foundPatient.set(patient);

        const status = this.resolvePatientStatus(patient);
        this.patientStatus.set(status);
        if (status === 'inconsistent') {
          this.errorMessage.set(
            'No fue posible validar tu estado de registro. Por favor contacta a soporte.'
          );
        }
      },
      error: (err: AppError) => {
        this.isLoading.set(false);
        if (err.errorCode === 'PATIENT_NOT_FOUND') {
          this.patientStatus.set('not-found');
          return;
        }
        this.errorMessage.set(err.message);
      },
    });
  }

  /**
   * Determina el estado del paciente a partir de la combinación de las 4
   * variables devueltas por el backend. Ver el comentario del tipo
   * `PatientStatus` para el detalle de cada caso.
   */
  private resolvePatientStatus(p: PatientPublicResponse): PatientStatus {
    const { patientExists, hasUserAccount, hasSystemUser, hasPatientRole } = p;

    // Paciente totalmente nuevo: no existe registro ni cuenta de ningún tipo.
    if (
      !patientExists &&
      !hasUserAccount &&
      !hasSystemUser &&
      !hasPatientRole
    ) {
      return 'not-found';
    }

    // Caso 1: el paciente existe pero no tiene cuenta vinculada.
    if (patientExists && !hasUserAccount && !hasPatientRole) {
      return 'found';
    }

    // Caso 2: ya tiene cuenta vinculada, pero no está registrado como
    // paciente.
    if (!patientExists && hasUserAccount && hasSystemUser) {
      return 'existing-user';
    }

    // Caso 3: paciente y cuenta existen, solo falta el rol de paciente.
    if (patientExists && hasUserAccount && hasSystemUser && !hasPatientRole) {
      return 'needs-patient-role';
    }

    // Caso 4: todo completo, no debe volver a registrarse.
    if (patientExists && hasUserAccount && hasSystemUser && hasPatientRole) {
      return 'already-linked';
    }

    return 'inconsistent';
  }

  onDocumentChange(value: string): void {
    this.documentNumber.set(value);
    this.patientStatus.set('idle');
    this.foundPatient.set(null);
    this.errorMessage.set('');
    this.successMessage.set('');
    this.errors.set({});
    this.password.set('');
    this.confirmPassword.set('');
    this.verificationCode.set('');
    this.resetForm();
  }

  goToStep2(): void {
    this.errorMessage.set('');
    this.successMessage.set('');

    if (this.patientStatus() === 'idle') return;
    if (this.patientStatus() === 'already-linked') return;
    if (this.patientStatus() === 'inconsistent') return;

    this.revalidateDocumentNumber();
    if (this.errors()['documentNumber']) return;

    if (this.patientStatus() === 'not-found') {
      const formOk = this.patientFormRef
        ? this.patientFormRef.validate()
        : true;
      if (!formOk) return;
    }

    if (this.patientStatus() === 'existing-user') {
      if (!this.validateExistingUserData()) return;
    }

    this.step.set(2);
  }

  goBack(): void {
    if (this.step() === 3) {
      this.step.set(2);
      this.errorMessage.set('');
      this.successMessage.set('');
      this.errors.set({});
      return;
    }

    if (this.step() === 2) {
      this.step.set(1);
      this.password.set('');
      this.confirmPassword.set('');
      this.verificationCode.set('');
      this.errors.set({});
      this.errorMessage.set('');
      this.successMessage.set('');
      return;
    }

    this.router.navigate(['/']);
  }

  submit(): void {
    if (!this.validateStep2()) return;

    this.isLoading.set(true);
    this.errorMessage.set('');
    this.successMessage.set('');

    if (
      this.isExistingPatient() ||
      this.isExistingSystemUser() ||
      this.needsPatientRoleOnly()
    ) {
      this.patientService
        .requestLinkUserAccountCode({ identification: this.documentNumber() })
        .subscribe({
          next: () => {
            this.isLoading.set(false);
            this.successMessage.set(
              'Se generó un código de verificación. Por ahora revísalo en la consola del backend.'
            );
            this.step.set(3);
          },
          error: (err: AppError) => this.handleError(err),
        });
      return;
    }

    // paciente nuevo real
    const f = this.form();

    this.patientService
      .createWithUser({
        username: this.documentNumber(),
        password: this.password(),
        identificationType: f.identificationType,
        identification: this.documentNumber(),
        firstName: f.firstName,
        lastName: f.lastName,
        phone: f.phone,
        email: (f.email ?? '').trim() || undefined,
        sex: f.sex,
        birthDate: f.birthDate,
        guardianPhone: (f.guardianPhone ?? '').trim() || undefined,
      })
      .subscribe({
        next: () => this.onSuccess(),
        error: (err: AppError) => this.handleError(err),
      });
  }

  confirmCode(): void {
    if (!this.validateStep3()) return;

    this.isLoading.set(true);
    this.errorMessage.set('');
    this.successMessage.set('');

    const isExistingUser = this.isExistingSystemUser();
    const extra = isExistingUser ? this.existingUserData() : null;

    this.patientService
      .confirmLinkUserAccount({
        identification: this.documentNumber(),
        code: this.verificationCode(),
        password: this.requiresPassword() ? this.password() : undefined,
        ...(extra && {
          sex: extra.sex,
          birthDate: extra.birthDate,
          guardianPhone: extra.guardianPhone.trim() || undefined,
        }),
      })
      .subscribe({
        next: () => this.onSuccess(),
        error: (err: AppError) => this.handleError(err),
      });
  }

  private onSuccess(): void {
    this.isLoading.set(false);
    this.keycloak.login({
      loginHint: this.documentNumber(),
      redirectUri: window.location.origin + '/paciente/agendar',
    });
  }

  /**
   * Maneja errores de las peticiones de los pasos 2 y 3 (crear cuenta,
   * vincular acceso, confirmar código).
   *
   * @param err - Error ya normalizado por el interceptor.
   */
  private handleError(err: AppError): void {
    this.isLoading.set(false);

    if (
      err.errorCode === 'INVALID_VERIFICATION_CODE' ||
      err.errorCode === 'VERIFICATION_CODE_EXPIRED' ||
      err.errorCode === 'VERIFICATION_CODE_BLOCKED'
    ) {
      this.verificationCode.set('');
    }

    this.errorMessage.set(err.message);
  }

  private validateStep2(): boolean {
    const newErrors: Record<string, string> = {};
    if (this.requiresPassword()) {
      if (this.password().length < this.PASSWORD_MIN) {
        newErrors['password'] =
          `La contraseña debe tener al menos ${this.PASSWORD_MIN} caracteres`;
      } else if (this.password().length > this.PASSWORD_MAX) {
        newErrors['password'] =
          `La contraseña no puede superar los ${this.PASSWORD_MAX} caracteres`;
      } else if (!this.PASSWORD_ALPHANUMERIC.test(this.password())) {
        newErrors['password'] = 'La contraseña debe contener letras y números';
      }

      if (this.password() !== this.confirmPassword()) {
        newErrors['confirmPassword'] = 'Las contraseñas no coinciden';
      }
    }

    this.errors.set(newErrors);
    return Object.keys(newErrors).length === 0;
  }

  private validateStep3(): boolean {
    const newErrors: Record<string, string> = {};

    if (!/^\d{6}$/.test(this.verificationCode().trim())) {
      newErrors['verificationCode'] =
        'Ingresa el código de verificación de 6 dígitos';
    }

    this.errors.set(newErrors);
    return Object.keys(newErrors).length === 0;
  }

  /**
   * Valida los 3 campos mínimos requeridos cuando la persona ya tiene
   * cuenta de usuario pero aún no está registrada como paciente: sexo y
   * fecha de nacimiento son obligatorios; el teléfono de acudiente solo es
   * obligatorio si, según la fecha ingresada, es menor de 18 años.
   */
  private validateExistingUserData(): boolean {
    const newErrors: Record<string, string> = {};
    const data = this.existingUserData();

    if (!data.sex) {
      newErrors['sex'] = 'Este campo es obligatorio';
    }

    if (!data.birthDate) {
      newErrors['birthDate'] = 'Ingrese una fecha de nacimiento válida';
    } else {
      const today = new Date();
      today.setHours(0, 0, 0, 0);
      const input = parseLocalDateString(data.birthDate);
      input.setHours(0, 0, 0, 0);
      if (input >= today) {
        newErrors['birthDate'] =
          'La fecha de nacimiento debe ser anterior a hoy';
      }
    }

    const guardianPhone = data.guardianPhone.trim();
    if (guardianPhone && !/^[0-9]{10}$/.test(guardianPhone)) {
      newErrors['guardianPhone'] =
        'Ingrese un número válido de exactamente 10 dígitos';
    } else if (this.isMinorExistingUser() && !guardianPhone) {
      newErrors['guardianPhone'] =
        'El celular del acudiente es obligatorio para menores de 18 años';
    }

    this.errors.set(newErrors);
    return Object.keys(newErrors).length === 0;
  }

  private calcAge(birthDate: Date): number {
    const today = new Date();
    let age = today.getFullYear() - birthDate.getFullYear();
    const m = today.getMonth() - birthDate.getMonth();
    if (m < 0 || (m === 0 && today.getDate() < birthDate.getDate())) age--;
    return age;
  }

  private resetForm(): void {
    this.form.set({ ...EMPTY_PATIENT_FORM });
    this.existingUserData.set({ ...EMPTY_EXISTING_USER_DATA });
    this.patientFormRef?.clearErrors();
  }
}
