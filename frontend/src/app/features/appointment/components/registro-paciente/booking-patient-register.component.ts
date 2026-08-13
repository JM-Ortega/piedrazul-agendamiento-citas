import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  output,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { LucideArrowLeft, LucideAlertCircle } from '@lucide/angular';
import { ButtonComponent } from '../../../../design-system/atoms/button/button.component';
import {
  InputComponent,
  SanitizeRule,
} from '../../../../design-system/atoms/input/input.component';
import { ConfirmModalComponent } from '../../../../design-system/organisms/confirm-modal/confirm-modal.component';
import { BookingStateService } from '../../services/booking-state.service';
import { NuevaCitaService } from '../../services/nuevaCita.service';
import {
  PatientDataFormComponent,
  PatientFormData,
} from '../../../../shared/components/form/register-form.component';
import {
  DOCUMENT_RULES,
  DEFAULT_DOCUMENT_MAX_LENGTH,
  validateDocumentForType,
} from '../../../../shared/helpers/document-validation';
import { AppError } from '../../../../shared/models/interfaces/api-error.model';

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
    LucideAlertCircle,
    ButtonComponent,
    InputComponent,
    ConfirmModalComponent,
    PatientDataFormComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './booking-patient-register.component.html',
})
export class BookingPatientRegisterComponent {
  protected state = inject(BookingStateService);
  private citaService = inject(NuevaCitaService);

  advance = output<void>();
  goBack = output<void>();
  documentAlreadyExists = output<string>();

  readonly maxBirthDate = new Date();
  readonly documentInputMaxLength = DEFAULT_DOCUMENT_MAX_LENGTH;

  documentNumber = signal(this.state.patientForm().identification);
  documentError = signal('');
  globalErrorMessage = signal('');
  isChecking = signal(false);

  showExistsModal = signal(false);
  existingDocument = signal('');

  documentSanitizeRule = computed<SanitizeRule>(() => {
    const type = this.state.patientForm().identificationType;
    if (!type) return 'alphanumeric';
    return DOCUMENT_RULES[type]?.sanitize ?? 'alphanumeric';
  });

  patientFormValue = computed<PatientFormData>(() => {
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    const { identification, ...rest } = this.state.patientForm();
    return rest;
  });

  onDocumentNumberChange(value: string | number | boolean | null): void {
    const clean = String(value ?? '');
    this.documentNumber.set(clean);
    this.state.patientForm.update((f) => ({ ...f, identification: clean }));
    this.documentError.set('');
  }

  onPatientFormChange(value: PatientFormData): void {
    this.state.patientForm.update((f) => ({ ...f, ...value }));
  }

  /**
   * Valida formato de documento y resto del formulario
   * Si todo es válido, verifica contra el backend si el
   * documento ya existe antes de avanzar.
   *
   * @param form - Referencia al organismo de datos del paciente, para invocar su validación.
   */
  onContinue(form: PatientDataFormComponent): void {
    const docErr = this.getDocumentFormatError();
    this.documentError.set(docErr);
    const formOk = form.validate();
    if (docErr || !formOk) return;
    this.checkDocumentExistsAndAdvance();
  }

  private getDocumentFormatError(): string {
    const doc = this.documentNumber().trim();
    if (!doc) return 'Este campo es obligatorio';

    const type = this.state.patientForm().identificationType;
    return validateDocumentForType(type, doc);
  }

  /**
   * Consulta si el documento ya existe en el sistema. Si existe, muestra el
   * modal de advertencia en vez de avanzar. Si no existe (`PATIENT_NOT_FOUND`),
   * continúa con el registro con normalidad.
   */
  private checkDocumentExistsAndAdvance(): void {
    const doc = this.documentNumber().trim();
    this.isChecking.set(true);

    this.citaService.getPatientByDocument(doc).subscribe({
      next: (patient) => {
        this.isChecking.set(false);
        if (patient) {
          this.existingDocument.set(doc);
          this.showExistsModal.set(true);
          return;
        }
        this.advance.emit();
      },
      error: (err: AppError) => {
        this.isChecking.set(false);
        if (err.errorCode === 'PATIENT_NOT_FOUND') {
          this.advance.emit();
          return;
        }
        this.globalErrorMessage.set(err.message);
      },
    });
  }

  confirmExistingDocument(): void {
    this.showExistsModal.set(false);
    this.documentAlreadyExists.emit(this.existingDocument());
  }

  dismissExistsModal(): void {
    this.showExistsModal.set(false);
  }

  onGoBack(): void {
    this.goBack.emit();
  }
}
