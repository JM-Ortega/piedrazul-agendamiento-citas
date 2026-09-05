import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  inject,
  signal,
} from '@angular/core';
import { Router } from '@angular/router';
import { DoctorService } from '../../../../../core/services/doctor.service';
import { SelectOption } from '../../../../../design-system/atoms/select/select.component';
import { Patient } from '../../../../../shared/models/interfaces/patient.model';
import { FormatoPipe } from '../../../../../shared/pipes/formatoPipe';
import { UnscheduledPatientSearchComponent } from '../unscheduledPatientSearch/unscheduledPatientSearch.component';
import { UnscheduledPatientAttendComponent } from '../unscheduledPatientAttend/unscheduledPatientAttend.component';
import { UnscheduledPatientRegisterComponent } from '../unscheduledPatientRegister/unscheduledPatientRegister.component';

type SubStep = 'search' | 'attend' | 'register';

/**
 * Orquesta el flujo de atención de un paciente sin cita previa: búsqueda
 * por documento → (encontrado: seleccionar tipo de atención y continuar a
 * control médico) o (no encontrado: registrar paciente + tipo de atención
 * + observación, guardando la atención directamente).
 */
@Component({
  selector: 'app-doctor-unscheduled-attention',
  standalone: true,
  imports: [
    UnscheduledPatientSearchComponent,
    UnscheduledPatientAttendComponent,
    UnscheduledPatientRegisterComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './doctorUnscheduledAttention.component.html',
})
export class DoctorUnscheduledAttentionComponent implements OnInit {
  private doctorService = inject(DoctorService);
  private router = inject(Router);
  private formatoPipe = new FormatoPipe();

  subStep = signal<SubStep>('search');
  lastSearchedDocument = signal('');
  foundPatient = signal<Patient | null>(null);
  specialtyOptions = signal<SelectOption[]>([]);

  ngOnInit(): void {
    this.doctorService.getMe().subscribe((doctor) => {
      this.specialtyOptions.set(
        (doctor.specialty ?? []).map((s) => ({
          value: s,
          label: this.formatoPipe.transform(s),
        }))
      );
    });
  }

  onFound(patient: Patient): void {
    this.foundPatient.set(patient);
    this.lastSearchedDocument.set(patient.identification);
    this.subStep.set('attend');
  }

  onNotFound(documentNumber: string): void {
    this.foundPatient.set(null);
    this.lastSearchedDocument.set(documentNumber);
    this.subStep.set('register');
  }

  onAttendGoBack(): void {
    this.foundPatient.set(null);
    this.lastSearchedDocument.set('');
    this.subStep.set('search');
  }

  onAttendAdvance(specialty: string): void {
    const documentNumber = this.foundPatient()?.identification ?? '';
    this.router.navigate(['/medico/control-medico/sin-cita'], {
      state: { documentNumber, specialty },
    });
  }

  onRegisterGoBack(): void {
    this.lastSearchedDocument.set('');
    this.subStep.set('search');
  }

  onRegisterAdvance(): void {
    this.router.navigate(['/medico']);
  }
}
