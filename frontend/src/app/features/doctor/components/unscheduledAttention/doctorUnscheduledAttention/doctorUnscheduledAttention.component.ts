import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  inject,
  signal,
} from '@angular/core';
import { Router } from '@angular/router';
import { DoctorService } from '../../../../../core/services/doctor.service';
import { SelectOption } from '../../../../../designSystem/atoms/select/select.component';
import { FormatoPipe } from '../../../../../shared/pipes/formatoPipe';
import {
  UnscheduledAttendanceStart,
  UnscheduledPatientSearchComponent,
} from '../unscheduledPatientSearch/unscheduledPatientSearch.component';
import { UnscheduledPatientRegisterComponent } from '../unscheduledPatientRegister/unscheduledPatientRegister.component';

type SubStep = 'search' | 'register';

/**
 * Orquesta el flujo de atención de un paciente sin cita previa: búsqueda
 * por documento → (encontrado: elige tipo de atención dentro del mismo
 * buscador y continúa a control médico) o (no encontrado: registrar
 * paciente + tipo de atención + observación, guardando la atención
 * directamente).
 */
@Component({
  selector: 'app-doctor-unscheduled-attention',
  standalone: true,
  imports: [
    UnscheduledPatientSearchComponent,
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

  onFound({ patient, specialty }: UnscheduledAttendanceStart): void {
    this.router.navigate(['/medico/control-medico/sin-cita'], {
      state: { documentNumber: patient.identification, specialty },
    });
  }

  onNotFound(documentNumber: string): void {
    this.lastSearchedDocument.set(documentNumber);
    this.subStep.set('register');
  }

  onExistingDocumentConfirmed(doc: string): void {
    this.lastSearchedDocument.set(doc);
    this.subStep.set('search');
  }

  onRegisterGoBack(): void {
    this.lastSearchedDocument.set('');
    this.subStep.set('search');
  }

  onRegisterAdvance(): void {
    this.router.navigate(['/medico']);
  }
}
