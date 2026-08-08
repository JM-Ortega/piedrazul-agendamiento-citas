import { CommonModule } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
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
import { mapHttpError } from '../../shared/helpers/http-errors';
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

type RegistroStep = 1 | 2 | 3;
type PatientStatus =
  'idle' | 'found' | 'already-linked' | 'not-found' | 'existing-user';

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

  password = signal('');
  confirmPassword = signal('');
  verificationCode = signal('');

  protected readonly PASSWORD_MIN = 6;
  protected readonly PASSWORD_MAX = 100;
  private readonly PASSWORD_ALPHANUMERIC = /^(?=.*[a-zA-Z])(?=.*[0-9]).+$/;
  readonly maxBirthDate = new Date();

  errors = signal<Record<string, string>>({});

  protected readonly DOC_MAX = 20;

  readonly isNewPatient = computed(() => this.patientStatus() === 'not-found');
  readonly isExistingPatient = computed(() => this.patientStatus() === 'found');
  readonly isExistingSystemUser = computed(
    () => this.patientStatus() === 'existing-user'
  );

  readonly requiresPassword = computed(
    () => this.isNewPatient() || this.isExistingPatient()
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

  protected readonly DOCUMENT_INPUT_MAX_LENGTH = 20;

  documentSanitizeRule = computed<SanitizeRule>(() => {
    const type = this.form().identificationType;
    if (!type) return 'alphanumeric';
    return DOCUMENT_RULES[type]?.sanitize ?? 'alphanumeric';
  });

  documentMaxLengthDynamic = computed<number>(() => {
    const type = this.form().identificationType;
    return DOCUMENT_RULES[type]?.max ?? DEFAULT_DOCUMENT_MAX_LENGTH;
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

        // paciente ya vinculado
        if (patient.hasUserAccount) {
          this.patientStatus.set('already-linked');
          return;
        }

        // paciente existente sin cuenta
        if (patient.patientExists) {
          this.patientStatus.set('found');
          return;
        }
        if (patient.hasSystemUser) {
          this.patientStatus.set('existing-user');
          return;
        }
        this.patientStatus.set('not-found');
      },
      error: (err: HttpErrorResponse) => {
        this.isLoading.set(false);
        if (err.status === 404) {
          this.patientStatus.set('not-found');
          return;
        }
        this.errorMessage.set(
          mapHttpError(err, 'Error al buscar el documento. Intenta de nuevo.')
        );
      },
    });
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

    this.revalidateDocumentNumber();
    const docErr = this.errors()['documentNumber'];
    const formOk = this.patientFormRef ? this.patientFormRef.validate() : true;

    if (docErr || !formOk) return;

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

    if (this.isExistingPatient() || this.isExistingSystemUser()) {
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
          error: (err) => this.handleError(err),
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
        error: (err) => this.handleError(err),
      });
  }

  confirmCode(): void {
    if (!this.validateStep3()) return;

    this.isLoading.set(true);
    this.errorMessage.set('');
    this.successMessage.set('');

    this.patientService
      .confirmLinkUserAccount({
        identification: this.documentNumber(),
        code: this.verificationCode(),
        // solo enviar password si aplica
        password: this.requiresPassword() ? this.password() : undefined,
      })
      .subscribe({
        next: () => this.onSuccess(),
        error: (err) => this.handleError(err),
      });
  }

  private onSuccess(): void {
    this.isLoading.set(false);
    this.keycloak.login({
      loginHint: this.documentNumber(),
      redirectUri: window.location.origin + '/paciente/agendar',
    });
  }

  private handleError(err: HttpErrorResponse): void {
    this.isLoading.set(false);
    const errorCode = err.error?.errorCode;

    switch (errorCode) {
      case 'PATIENT_ALREADY_LINKED':
        this.errorMessage.set('Este paciente ya tiene una cuenta asociada.');
        break;
      case 'USERNAME_TAKEN':
        this.errorMessage.set(
          'Ya existe una cuenta asociada a este documento.'
        );
        break;
      case 'INVALID_VERIFICATION_CODE':
        this.errorMessage.set('El código de verificación es inválido.');
        break;
      case 'VERIFICATION_CODE_EXPIRED':
        this.errorMessage.set('El código de verificación expiró.');
        break;
      case 'VERIFICATION_CODE_BLOCKED':
        this.errorMessage.set(
          'El código fue bloqueado por exceso de intentos.'
        );
        break;
      default:
        this.errorMessage.set(
          mapHttpError(
            err,
            'Ocurrió un error al crear la cuenta. Intenta de nuevo.'
          )
        );
        break;
    }
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
    this.form.set({ ...EMPTY_PATIENT_FORM });
    this.patientFormRef?.clearErrors();
  }
}
