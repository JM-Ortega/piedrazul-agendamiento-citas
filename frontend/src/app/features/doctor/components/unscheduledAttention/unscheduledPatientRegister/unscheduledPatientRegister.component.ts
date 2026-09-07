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
import { ButtonComponent } from '../../../../../design-system/atoms/button/button.component';
import {
  SelectComponent,
  SelectOption,
} from '../../../../../design-system/atoms/select/select.component';
import { DoctorService } from '../../../../../core/services/doctor.service';
import {
  PatientFormComponent,
  PatientFormData,
  EMPTY_PATIENT_FORM,
} from '../../../../../shared/components/forms/patient-form/patient-form.component';
import { UnscheduledAttention } from '../../../../../shared/models/dtos/unscheduledAttention.dto';

const OBSERVATION_MAX_LENGTH = 300;

/**
 * Registro de un paciente nuevo + captura de la observación clínica para
 * el flujo de atención sin cita previa. Guarda todo en una sola petición
 * vía `registerUnscheduledAttention` (no hay navegación a control médico).
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

  readonly maxBirthDate = new Date();
  readonly observationMaxLength = OBSERVATION_MAX_LENGTH;

  patientFormValue = signal<PatientFormData>({ ...EMPTY_PATIENT_FORM });
  selectedSpecialty = signal('');
  specialtyError = signal('');
  observation = signal('');
  saveError = signal('');
  isSaving = signal(false);

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

  onSave(form: PatientFormComponent): void {
    if (!form.validate()) return;
    if (!this.selectedSpecialty()) {
      this.saveError.set('Debe seleccionar el tipo de atención.');
      return;
    }

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
}
