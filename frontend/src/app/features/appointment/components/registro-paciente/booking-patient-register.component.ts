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
    this.revalidateDocumentNumber();
  }

  onPatientFormChange(value: PatientFormData): void {
    this.state.patientForm.update((f) => ({ ...f, ...value }));
    this.revalidateDocumentNumber();
  }

  /**
   * Revalida el documento contra la regla del tipo actualmente elegido
   * mientras el usuario escribe o cambia el tipo.
   */
  private revalidateDocumentNumber(): void {
    const type = this.state.patientForm().identificationType;
    if (!type) {
      this.documentError.set('');
      return;
    }

    const doc = this.documentNumber().trim();
    if (!doc) {
      this.documentError.set('Este campo es obligatorio');
      return;
    }

    this.documentError.set(validateDocumentForType(type, doc));
  }

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
      error: (err) => {
        this.isChecking.set(false);
        if (err.status === 404) {
          this.advance.emit();
          return;
        }
        this.documentError.set(
          'Error al verificar el documento. Intente de nuevo.'
        );
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
