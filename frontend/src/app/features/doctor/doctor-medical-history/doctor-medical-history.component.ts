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
  LucideAlertCircle,
  LucideCalendar,
  LucideClipboardPen,
  LucideClipboardPlus,
  LucideFolderOpen,
  LucideSave,
} from '@lucide/angular';
import { CanComponentDeactivate } from '../../../core/guards/canDeactivate.guard';
import { DoctorService } from '../../../core/services/doctor.service';
import { ButtonComponent } from '../../../design-system/atoms/button/button.component';
import { PaginationComponent } from '../../../design-system/molecules/pagination/pagination.component';
import { ConfirmModalComponent } from '../../../design-system/organisms/confirm-modal/confirm-modal.component';
import { Patient } from '../../../shared/models/interfaces/patient.model';
import { FormatoPipe } from '../../../shared/pipes/formatoPipe';

@Component({
  selector: 'app-doctor-medical-history',
  templateUrl: './doctor-medical-history.component.html',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    LucideAlertCircle,
    LucideClipboardPen,
    LucideClipboardPlus,
    LucideSave,
    LucideFolderOpen,
    LucideCalendar,
    FormatoPipe,
    ButtonComponent,
    PaginationComponent,
    ConfirmModalComponent,
  ],
})
export class DoctorMedicalHistoryComponent
  implements OnInit, CanComponentDeactivate
{
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  readonly doctorService = inject(DoctorService);

  /** Longitud máxima permitida para la observación de la historia clínica. */
  readonly OBSERVATION_MAX_LENGTH = 300;

  readonly mostrarInfo = signal(false);
  toggleInfo() {
    this.mostrarInfo.update((v) => !v);
  }

  readonly records = this.doctorService.medicalRecordsState.content;
  readonly medicalRecordsPagination =
    this.doctorService.medicalRecordsState.pagination;
  readonly patient = signal<Patient | undefined>(undefined);
  readonly isLoadingPatient = signal(true);
  readonly newObservation = signal('');
  /** Caracteres restantes antes de llegar al límite, para mostrar en el contador del textarea. */
  readonly remainingObservationChars = computed(
    () => this.OBSERVATION_MAX_LENGTH - this.newObservation().length
  );
  private readonly idAppointment = signal<string>('');
  readonly saveError = signal('');
  readonly isSaving = signal(false);
  readonly isLoadingRecords = this.doctorService.isLoadingRecords;

  // ── Salida de la ruta (CanDeactivate) ────────────────────────────────────
  /** Modal de confirmación al intentar salir sin haber guardado la atención. */
  readonly showExitConfirmModal = signal(false);
  /** Se pone en true justo antes de navegar programáticamente tras guardar,
   * para no mostrar el modal de confirmación en ese caso. */
  private allowNavigation = false;
  private exitResolver: ((value: boolean) => void) | null = null;

  /**
   * Invocado por `unsavedChangesGuard` al intentar salir de esta ruta,
   * sin importar si la salida es por navegación programática, un enlace,
   * o el botón "atrás" del navegador.
   */
  canDeactivate(): boolean | Promise<boolean> {
    if (this.allowNavigation) return true;
    return new Promise<boolean>((resolve) => {
      this.exitResolver = resolve;
      this.showExitConfirmModal.set(true);
    });
  }

  /** El usuario confirma que desea salir: la cita queda sin atender. */
  confirmExit(): void {
    this.showExitConfirmModal.set(false);
    this.exitResolver?.(true);
    this.exitResolver = null;
  }

  /** El usuario cancela: permanece en el formulario, sin alterar la navegación. */
  cancelExit(): void {
    this.showExitConfirmModal.set(false);
    this.exitResolver?.(false);
    this.exitResolver = null;
  }

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

    this.doctorService.getPatientByAppointment(idAppointment).subscribe({
      next: (patient) => {
        this.patient.set(patient);
        this.isLoadingPatient.set(false);
        this.doctorService.loadMedicalRecordsByPatient(patient.id);
      },
      error: () => {
        this.isLoadingPatient.set(false);
      },
    });
  }

  /**
   * Maneja el cambio de página emitido por `<app-pagination>` para el historial clínico del paciente actual.
   *
   * @param page - Número de página (base 0) al que se quiere navegar.
   */
  onMedicalRecordsPageChange(page: number): void {
    const patientId = this.patient()?.id;
    if (patientId) {
      this.doctorService.loadMedicalRecordsByPatient(patientId, page);
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
          this.allowNavigation = true;
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
