import {
  ChangeDetectionStrategy,
  Component,
  EventEmitter,
  Input,
  OnInit,
  Output,
  ViewChild,
  inject,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { LucideSave, LucideTriangleAlert } from '@lucide/angular';
import { DoctorService } from '../../../../core/services/doctor.service';
import { ButtonComponent } from '../../../../designSystem/atoms/button/button.component';
import {
  EMPTY_PATIENT_FORM,
  PatientFormComponent,
  PatientFormData,
} from '../../../../shared/components/forms/patientForm/patientForm.component';
import { PatientUpdatePayload } from '../../../../shared/models/dtos/PatientUpdatePayload';
import { AppError } from '../../../../shared/models/interfaces/apiError.model';
import { Patient } from '../../../../shared/models/interfaces/patient.model';

/**
 * Panel de edición de los datos completos de un paciente. Encapsula el
 * formulario reutilizable (`app-patient-data-form`), su validación, el
 * armado del payload para el backend y el estado de guardado/error.
 * El padre solo necesita el `Patient` de entrada y escuchar `saved`/`cancelled`.
 */
@Component({
  selector: 'app-patient-edit-panel',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    FormsModule,
    PatientFormComponent,
    ButtonComponent,
    LucideSave,
    LucideTriangleAlert,
  ],
  templateUrl: './patientEditPanel.component.html',
})
export class PatientEditPanelComponent implements OnInit {
  private doctorService = inject(DoctorService);

  // ── Inputs / Outputs ────────────────────────────────────────────────────
  @Input({ required: true }) patient!: Patient;
  @Output() saved = new EventEmitter<Patient>();
  @Output() cancelled = new EventEmitter<void>();

  @ViewChild(PatientFormComponent) private formRef?: PatientFormComponent;

  // ── Estado interno ──────────────────────────────────────────────────────
  /** Copia editable de los datos del paciente, sincronizada con el formulario. */
  readonly formData = signal<PatientFormData>({ ...EMPTY_PATIENT_FORM });
  readonly isSaving = signal(false);
  readonly saveError = signal('');

  // ── Lifecycle ───────────────────────────────────────────────────────────
  /** Precarga el formulario con los datos actuales del paciente. */
  ngOnInit(): void {
    this.formData.set(this.toFormData(this.patient));
  }

  // ── Formulario ──────────────────────────────────────────────────────────
  /** Sincroniza el estado local con cada cambio emitido por el formulario. */
  onFormChange(value: PatientFormData): void {
    this.formData.set(value);
  }

  // ── Guardar / Cancelar ──────────────────────────────────────────────────
  /** Descarta la edición sin guardar. */
  cancel(): void {
    this.cancelled.emit();
  }

  /** Valida el formulario y envía la actualización al backend. */
  save(): void {
    if (!this.formRef?.validate()) return;

    const v = this.formData();
    const payload: PatientUpdatePayload = {
      identificationType: v.identificationType,
      identification: v.identification,
      firstName: v.firstName,
      lastName: v.lastName,
      phone: v.phone,
      sex: v.sex,
      birthDate: v.birthDate,
      email: v.email?.trim() || null,
      guardianPhone: v.guardianPhone?.trim() || null,
    };

    this.saveError.set('');
    this.isSaving.set(true);

    this.doctorService.updatePatient(this.patient.id, payload).subscribe({
      next: (updated) => {
        this.isSaving.set(false);
        this.saved.emit(updated);
      },
      error: (err: AppError) => {
        this.isSaving.set(false);
        this.saveError.set(err.message);
      },
    });
  }

  // ── Helpers ─────────────────────────────────────────────────────────────
  /** Convierte el `Patient` recibido al shape que espera `app-patient-data-form`. */
  private toFormData(p: Patient): PatientFormData {
    return {
      identificationType: p.identificationType,
      identification: p.identification,
      firstName: p.firstName,
      lastName: p.lastName,
      phone: p.phone,
      sex: p.sex,
      birthDate: p.birthDate,
      email: p.email ?? '',
      guardianPhone: p.guardianPhone ?? '',
    };
  }
}
