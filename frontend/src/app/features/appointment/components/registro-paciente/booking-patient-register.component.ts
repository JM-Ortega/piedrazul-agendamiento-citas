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
import { ConfirmModalComponent } from '../../../../design-system/organisms/confirm-modal/confirm-modal.component';
import { BookingStateService } from '../../services/booking-state.service';
import { NuevaCitaService } from '../../services/nuevaCita.service';
import {
  PatientFormComponent,
  PatientFormData,
} from '../../../../shared/components/forms/patient-form/patient-form.component';
import { AppError } from '../../../../shared/models/interfaces/api-error.model';

/**
 * Capturar y validar los datos de un paciente que no fue encontrado en el sistema para
 * que pueda ser registrado al confirmar la cita. El documento se captura y valida
 * dentro de `PatientFormComponent` (vía `showDocumentNumber`).
 */
@Component({
  selector: 'app-booking-patient-register',
  standalone: true,
  imports: [
    FormsModule,
    LucideArrowLeft,
    LucideAlertCircle,
    ButtonComponent,
    ConfirmModalComponent,
    PatientFormComponent,
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

  globalErrorMessage = signal('');
  isChecking = signal(false);

  showExistsModal = signal(false);
  existingDocument = signal('');

  patientFormValue = computed<PatientFormData>(() => this.state.patientForm());

  onPatientFormChange(value: PatientFormData): void {
    this.state.patientForm.set(value);
  }

  /**
   * Valida el formulario completo (incluyendo el documento). Si es válido,
   * verifica contra el backend si el documento ya existe antes de avanzar.
   *
   * @param form - Referencia al organismo de datos del paciente, para invocar su validación.
   */
  onContinue(form: PatientFormComponent): void {
    if (!form.validate()) return;
    this.checkDocumentExistsAndAdvance();
  }

  /**
   * Consulta si el documento ya existe en el sistema. Si existe, muestra el
   * modal de advertencia en vez de avanzar. Si no existe (`PATIENT_NOT_FOUND`),
   * continúa con el registro con normalidad.
   */
  private checkDocumentExistsAndAdvance(): void {
    const doc = this.state.patientForm().identification.trim();
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
