import {
  ChangeDetectionStrategy,
  Component,
  Input,
  computed,
  inject,
  output,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  LucideArrowLeft,
  LucideSave,
  LucideAlertTriangle,
  LucideAlertCircle,
} from '@lucide/angular';
import { ButtonComponent } from '../../../../../designSystem/atoms/button/button.component';
import {
  SelectComponent,
  SelectOption,
} from '../../../../../designSystem/atoms/select/select.component';
import { ConfirmModalComponent } from '../../../../../designSystem/organisms/confirmModal/confirmModal.component';
import { DoctorService } from '../../../../../core/services/doctor.service';
import {
  PatientFormComponent,
  PatientFormData,
  EMPTY_PATIENT_FORM,
} from '../../../../../shared/components/forms/patientForm/patientForm.component';
import { UnscheduledAttention } from '../../../../../shared/models/dtos/unscheduledAttention.dto';
import { AppError } from '../../../../../shared/models/interfaces/api-error.model';

const OBSERVATION_MAX_LENGTH = 300;

/**
 * Registro de un paciente nuevo + captura de la observación clínica para
 * el flujo de atención sin cita previa.
 */
@Component({
  selector: 'app-unscheduled-patient-register',
  standalone: true,
  imports: [
    FormsModule,
    LucideArrowLeft,
    LucideSave,
    LucideAlertTriangle,
    LucideAlertCircle,
    ButtonComponent,
    SelectComponent,
    PatientFormComponent,
    ConfirmModalComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './unscheduledPatientRegister.component.html',
})
export class UnscheduledPatientRegisterComponent {
  private doctorService = inject(DoctorService);

  @Input() set prefillDocument(value: string) {
    if (!value) return;
    this.patientFormValue.update((f) => ({ ...f, identification: value }));
  }
  @Input() specialtyOptions: SelectOption[] = [];

  goBack = output<void>();
  advance = output<void>();
  documentAlreadyExists = output<string>();

  readonly maxBirthDate = new Date();
  readonly observationMaxLength = OBSERVATION_MAX_LENGTH;

  patientFormValue = signal<PatientFormData>({ ...EMPTY_PATIENT_FORM });
  selectedSpecialty = signal('');
  specialtyError = signal('');
  observation = signal('');
  saveError = signal('');
  isSaving = signal(false);

  showExistsModal = signal(false);
  existingDocument = signal('');

  readonly remainingObservationChars = computed(
    () => this.observationMaxLength - this.observation().length
  );

  onPatientFormChange(value: PatientFormData): void {
    this.patientFormValue.set(value);
  }

  onSpecialtyChange(value: string): void {
    this.selectedSpecialty.set(value);
    if (value) this.specialtyError.set('');
  }

  onObservationChange(value: string): void {
    this.observation.set(value.slice(0, this.observationMaxLength));
  }

  onGoBack(): void {
    this.goBack.emit();
  }

  /**
   * Valida el formulario y la especialidad. Si ambos son válidos, verifica
   * primero contra el backend si el documento ya existe (pudo ser editado
   * en el formulario) antes de guardar la atención.
   */
  onSave(form: PatientFormComponent): void {
    if (!form.validate()) return;
    if (!this.selectedSpecialty()) {
      this.specialtyError.set('Debe seleccionar el tipo de atención.');
      return;
    }
    this.specialtyError.set('');
    this.checkDocumentAndSave();
  }

  private checkDocumentAndSave(): void {
    const doc = this.patientFormValue().identification.trim();
    this.saveError.set('');
    this.isSaving.set(true);

    this.doctorService.getPatientByDocument(doc).subscribe({
      next: (patient) => {
        this.isSaving.set(false);
        if (patient) {
          this.existingDocument.set(doc);
          this.showExistsModal.set(true);
          return;
        }
        this.saveUnscheduledAttention();
      },
      error: (err: AppError) => {
        this.isSaving.set(false);
        if (err.errorCode === 'PATIENT_NOT_FOUND') {
          this.saveUnscheduledAttention();
          return;
        }
        this.saveError.set(err.message);
      },
    });
  }

  private saveUnscheduledAttention(): void {
    const p = this.patientFormValue();
    const request: UnscheduledAttention = {
      documentType: p.identificationType,
      documentNumber: p.identification,
      firstName: p.firstName,
      lastName: p.lastName,
      phone: p.phone,
      gender: p.sex,
      birthDate: p.birthDate,
      email: p.email,
      guardianPhone: p.guardianPhone,
      specialty: this.selectedSpecialty(),
      medicalCheckup:
        this.observation().trim().slice(0, this.observationMaxLength) || null,
    };

    this.isSaving.set(true);

    this.doctorService.registerUnscheduledAttention(request).subscribe({
      next: () => {
        this.isSaving.set(false);
        this.advance.emit();
      },
      error: (err) => {
        this.isSaving.set(false);
        this.saveError.set(
          err?.error?.message || 'Ocurrió un error al guardar la atención'
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
}
