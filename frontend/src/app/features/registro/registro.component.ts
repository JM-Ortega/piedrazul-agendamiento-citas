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
  PatientPublicResponse,
  PatientService,
} from '../../core/services/patient.service';
import {
  PatientFormData,
  EMPTY_PATIENT_FORM,
  PatientFormComponent,
} from '../../shared/components/forms/patient-form/patient-form.component';
import {
  ExistingUserFormData,
  EMPTY_GUARDIAN_FORM,
  ExistingUserFormComponent,
} from '../../shared/components/forms/existing-users-form/existing-user-form.component';
import { FormatoPipe } from '../../shared/pipes/formatoPipe';
import { SanitizeRule } from '../../design-system/atoms/input/input.component';
import {
  DOCUMENT_RULES,
  DEFAULT_DOCUMENT_MAX_LENGTH,
  validateDocumentForType,
} from '../../shared/helpers/document-validation';
import { AppError } from '../../shared/models/interfaces/api-error.model';

type RegistroStep = 1 | 2 | 3;

/**
 * Estado del paciente según la combinación de `patientExists`, `hasUserAccount`, `hasSystemUser` y `hasPatientRole`
 * devuelta por `/patients/document/{doc}/public`:
 *
 * - 'not-found': paciente totalmente nuevo (F, F, F, F). Formulario completo + contraseña. No requiere OTP.
 * - 'found': el paciente existe pero no tiene cuenta vinculada (T, F, F/T, F). Requiere OTP + contraseña.
 * - 'existing-user': ya existe una cuenta vinculada pero no está registrado como paciente (F, T, T, F/T).
 *    Requiere OTP + sexo/fecha nacimiento/teléfono de acudiente. No requiere contraseña.
 * - 'needs-patient-role': paciente y cuenta existen, falta el rol de paciente (T, T, T, F). Solo requiere OTP.
 * - 'already-linked': todo completo (T, T, T, T). No debe volver a pasar por el registro.
 * - 'inconsistent': cualquier otra combinación.
 */
type PatientStatus =
  | 'idle'
  | 'not-found'
  | 'found'
  | 'existing-user'
  | 'needs-patient-role'
  | 'already-linked'
  | 'inconsistent';

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
    PatientFormComponent,
    ExistingUserFormComponent,
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

  @ViewChild('patientForm') patientFormRef?: PatientFormComponent;
  @ViewChild('existingUserForm') guardianFormRef?: ExistingUserFormComponent;

  readonly Eye = LucideEye;
  readonly EyeOff = LucideEyeOff;

  step = signal<RegistroStep>(1);
  isLoading = signal(false);
  errorMessage = signal('');
  successMessage = signal('');
  showPassword = signal(false);
  showConfirmPassword = signal(false);

  documentNumber = signal('');
  patientStatus = signal<PatientStatus>('idle');
  foundPatient = signal<PatientPublicResponse | null>(null);

  /** Todos los datos para el caso en el que no existe usuario ni paciente. */
  newPatientForm = signal<PatientFormData>({ ...EMPTY_PATIENT_FORM });

  /** Datos mínimos para el caso 'existing-user' (sexo, fecha de nacimiento, acudiente). */
  existingUsersForm = signal<ExistingUserFormData>({ ...EMPTY_GUARDIAN_FORM });

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

    const f = this.newPatientForm();
    if (f.firstName || f.lastName) {
      return `${f.firstName} ${f.lastName}`.trim();
    }

    return '';
  });

  documentSanitizeRule = computed<SanitizeRule>(() => {
    const type = this.newPatientForm().identificationType;
    if (!type) return 'alphanumeric';
    return DOCUMENT_RULES[type]?.sanitize ?? 'alphanumeric';
  });

  documentMaxLengthDynamic = computed<number>(() => {
    const type = this.newPatientForm().identificationType;
    return DOCUMENT_RULES[type]?.max ?? DEFAULT_DOCUMENT_MAX_LENGTH;
  });

  /** Actualiza los datos del formulario de nuevos pacientes (se registran todos los datos) */
  onNewPatientFormChange(value: PatientFormData): void {
    this.newPatientForm.set(value);
    this.revalidateDocumentNumber();
  }

  /** Actualiza los datos del formulario de usuarios existentes (se registran los datos necesarios) */
  onExistingUsersFormChange(value: ExistingUserFormData): void {
    this.existingUsersForm.set(value);
  }

  private revalidateDocumentNumber(): void {
    const type = this.newPatientForm().identificationType;
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
      const formOk = this.guardianFormRef
        ? this.guardianFormRef.validate()
        : true;
      if (!formOk) return;
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
    const f = this.newPatientForm();

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
    const extra = isExistingUser ? this.existingUsersForm() : null;

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

  private resetForm(): void {
    this.newPatientForm.set({ ...EMPTY_PATIENT_FORM });
    this.existingUsersForm.set({ ...EMPTY_GUARDIAN_FORM });
    this.patientFormRef?.clearErrors();
    this.guardianFormRef?.clearErrors();
  }
}
