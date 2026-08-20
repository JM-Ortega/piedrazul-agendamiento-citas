import {
  ChangeDetectionStrategy,
  Component,
  computed,
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

  /** Longitud máxima permitida para la observación de la historia clínica. */
  readonly OBSERVATION_MAX_LENGTH = 300;

  readonly mostrarInfo = signal(false);
  toggleInfo() {
    this.mostrarInfo.update((v) => !v);
  }

  readonly records = this.doctorService.medicalRecords;
  readonly patient = signal<Patient | undefined>(undefined);
  readonly newObservation = signal('');
  /** Caracteres restantes antes de llegar al límite, para mostrar en el contador del textarea. */
  readonly remainingObservationChars = computed(
    () => this.OBSERVATION_MAX_LENGTH - this.newObservation().length
  );
  private readonly idAppointment = signal<string>('');
  readonly saveError = signal('');
  readonly isSaving = signal(false);
  readonly hasMoreRecords = computed(() => this.doctorService.hasMoreRecords);
  readonly isLoadingRecords = this.doctorService.isLoadingRecords;

  /**
   * Actualiza la observación truncándola a {@link OBSERVATION_MAX_LENGTH}
   * caracteres, para evitar que el usuario supere el límite incluso si
   * pega texto largo.
   *
   * @param value - Valor crudo emitido por el evento `input` del textarea.
   */
  onObservationChange(value: string): void {
    this.newObservation.set(value.slice(0, this.OBSERVATION_MAX_LENGTH));
  }

  ngOnInit(): void {
    const idAppointment =
      this.route.snapshot.paramMap.get('idAppointment') ?? '';
    this.idAppointment.set(idAppointment);

    this.doctorService.resetMedicalRecords();

    this.doctorService
      .getPatientByAppointment(idAppointment)
      .subscribe((patient) => {
        this.patient.set(patient);
        this.doctorService.loadMedicalRecordsByPatient(patient.id);
      });
  }

  loadMoreRecords(): void {
    const patientId = this.patient()?.id;
    if (patientId) {
      this.doctorService.loadMedicalRecordsByPatient(patientId);
    }
  }

  confirmAttendanceAndExit(): void {
    const idCita = this.idAppointment();
    if (!idCita) return;

    const observation =
      this.newObservation().trim().slice(0, this.OBSERVATION_MAX_LENGTH) ||
      null;

    this.saveError.set('');
    this.isSaving.set(true);

    this.doctorService
      .updateAppointmentAsAttended(idCita, observation)
      .subscribe({
        next: () => {
          this.doctorService.resetMedicalRecords();
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
