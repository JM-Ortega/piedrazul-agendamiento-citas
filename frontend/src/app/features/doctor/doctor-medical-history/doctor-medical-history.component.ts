import {
  ChangeDetectionStrategy,
  Component,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import {
  LucideCalendar,
  LucideClipboardPen,
  LucideClipboardPlus,
  LucideFolderOpen,
  LucideSave,
} from '@lucide/angular';
import { DoctorService } from '../../../core/services/doctor.service';
import { ButtonComponent } from '../../../design-system/atoms/button/button.component';
import { Patient } from '../../../shared/models/interfaces/patient.model';
import { FormatoPipe } from '../../../shared/pipes/formatoPipe';

@Component({
  selector: 'app-doctor-medical-history',
  templateUrl: './doctor-medical-history.component.html',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    LucideClipboardPen,
    LucideClipboardPlus,
    LucideSave,
    LucideFolderOpen,
    LucideCalendar,
    FormatoPipe,
    ButtonComponent,
  ],
})
export class DoctorMedicalHistoryComponent implements OnInit {
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  readonly doctorService = inject(DoctorService);

  readonly mostrarInfo = signal(false);
  toggleInfo() {
    this.mostrarInfo.update((v) => !v);
  }

  readonly records = this.doctorService.medicalRecords;
  readonly patient = signal<Patient | undefined>(undefined);
  readonly newObservation = signal('');
  private readonly idAppointment = signal<string>('');
  readonly saveError = signal('');
  readonly isSaving = signal(false);

  ngOnInit(): void {
    const idAppointment =
      this.route.snapshot.paramMap.get('idAppointment') ?? '';
    this.idAppointment.set(idAppointment);

    this.doctorService
      .getPatientByAppointment(idAppointment)
      .subscribe((patient) => {
        this.patient.set(patient);
        this.doctorService.loadMedicalRecordsByPatient(patient.id);
      });
  }

  confirmAttendanceAndExit(): void {
    const idCita = this.idAppointment();
    if (!idCita) return;

    const observation = this.newObservation().trim() || null;

    this.saveError.set('');
    this.isSaving.set(true);

    this.doctorService
      .updateAppointmentAsAttended(idCita, observation)
      .subscribe({
        next: () => {
          this.router.navigate(['/medico']);
        },
        error: (err) => {
          this.isSaving.set(false);
          this.saveError.set(
            err?.error?.message ||
              'Ocurrió un error al guardar la historia clínica'
          );
        },
      });
  }
}
