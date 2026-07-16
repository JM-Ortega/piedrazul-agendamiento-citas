import {
  ChangeDetectionStrategy,
  Component,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import {
  LucideArrowLeft,
  LucideCalendar,
  LucideClipboardPen,
  LucideClipboardPlus,
  LucideFolderOpen,
  LucideSave,
} from '@lucide/angular';
import { DoctorService } from '../../../core/services/doctor.service';
import { Patient } from '../../../shared/models/interfaces/patient.model';
import { FormatoPipe } from '../../../shared/pipes/formatoPipe';

@Component({
  selector: 'app-doctor-medical-history',
  templateUrl: './doctor-medical-history.component.html',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    LucideClipboardPen,
    LucideArrowLeft,
    LucideClipboardPlus,
    LucideSave,
    LucideFolderOpen,
    LucideCalendar,
    FormatoPipe,
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

  readonly hasSavedRecord = signal(false);
  readonly showExitModal = signal(false);

  readonly records = this.doctorService.medicalRecords;
  readonly patient = signal<Patient | undefined>(undefined);
  readonly newObservation = signal('');
  private readonly idAppointment = signal<string>('');
  readonly saveError = signal('');
  readonly saveSuccess = signal('');

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

  handleBack(): void {
    if (this.hasSavedRecord()) {
      this.hasSavedRecord.set(false);
      this.router.navigate(['/medico']);
    } else {
      this.showExitModal.set(true);
    }
  }

  confirmExit(): void {
    this.showExitModal.set(false);
    this.router.navigate(['/medico']);
  }

  cancelExit(): void {
    this.showExitModal.set(false);
  }

  addRecord(): void {
    const observations = this.newObservation().trim();
    const idCita = this.idAppointment();

    if (!observations || !idCita) return;
    this.saveError.set('');
    this.saveSuccess.set('');
    this.doctorService.addMedicalRecord(idCita, observations).subscribe({
      next: (saved) => {
        this.doctorService.medicalRecords.update((current) => [
          saved,
          ...current,
        ]);
        this.newObservation.set('');
        this.saveSuccess.set('Historia clínica guardada correctamente');
        this.hasSavedRecord.set(true);
      },
      error: (err) => {
        this.saveError.set(
          err?.error?.message ||
            'Ocurrió un error al guardar la historia clínica'
        );
      },
    });
  }
}
