import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  OnInit,
  output,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { LucideArrowLeft } from '@lucide/angular';
import { PatientService } from '../../../../core/services/patient.service';
import { ButtonComponent } from '../../../../design-system/atoms/button/button.component';
import { BookingStateService } from '../../services/booking-state.service';
import {
  PatientDataFormComponent,
  PatientFormData,
} from '../../../../shared/components/form/register-form.component';

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
    ButtonComponent,
    PatientDataFormComponent,
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

  readonly maxBirthDate = new Date();

  getPatientField<K extends keyof ReturnType<typeof this.state.patientForm>>(
    key: K
  ) {
    return this.state.patientForm()[key];
  }

  patientFormValue = computed<PatientFormData>(() => {
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    const { identification, ...rest } = this.state.patientForm();
    return rest;
  });

  onPatientFormChange(value: PatientFormData): void {
    this.state.patientForm.update((f) => ({ ...f, ...value }));
  }

  onContinue(form: PatientDataFormComponent): void {
    if (!form.validate()) return;
    this.advance.emit();
  }

  onGoBack(): void {
    this.goBack.emit();
  }
}
