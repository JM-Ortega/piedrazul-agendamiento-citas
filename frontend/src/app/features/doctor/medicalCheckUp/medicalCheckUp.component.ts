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
  LucideTriangleAlert,
} from '@lucide/angular';
import { CanComponentDeactivate } from '../../../core/guards/canDeactivate.guard';
import { DoctorService } from '../../../core/services/doctor.service';
import { ButtonComponent } from '../../../design-system/atoms/button/button.component';
import { PaginationComponent } from '../../../design-system/molecules/pagination/pagination.component';
import { ConfirmModalComponent } from '../../../design-system/organisms/confirm-modal/confirm-modal.component';
import { calcAge } from '../../../shared/helpers/patient-validation';
import { parseLocalDateString } from '../../../shared/helpers/transform-date-local';
import { Patient } from '../../../shared/models/interfaces/patient.model';
import { FormatoPipe } from '../../../shared/pipes/formatoPipe';
import { UnscheduledAttention } from '../../../shared/models/dtos/unscheduledAttention.dto';

type MedicalHistoryContext = 'scheduled' | 'unscheduled';

@Component({
  selector: 'app-doctor-medical-history',
  templateUrl: './medicalCheckUp.component.html',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    LucideTriangleAlert,
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

  private readonly UNSCHEDULED_CONTEXT_KEY =
    'doctor-unscheduled-attention-context';
  private readonly context = signal<MedicalHistoryContext>('scheduled');
  readonly isScheduledContext = computed(() => this.context() === 'scheduled');
  private readonly unscheduledSpecialty = signal<string>('');

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

  readonly patientBirthDateFormatted = computed(() => {
    const p = this.patient();
    if (!p?.birthDate) return 'No registra';
    return parseLocalDateString(p.birthDate).toLocaleDateString('es-CO', {
      day: '2-digit',
      month: '2-digit',
      year: 'numeric',
    });
  });

  readonly patientAge = computed(() => {
    const p = this.patient();
    if (!p?.birthDate) return null;
    return calcAge(parseLocalDateString(p.birthDate));
  });

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
    if (!this.isScheduledContext()) {
      this.clearUnscheduledContext();
    }
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
    this.doctorService.resetMedicalRecords();

    const idAppointment = this.route.snapshot.paramMap.get('idAppointment');
    if (idAppointment) {
      this.context.set('scheduled');
      this.idAppointment.set(idAppointment);
      this.loadPatientByAppointment(idAppointment);
      return;
    }

    this.context.set('unscheduled');
    const navigationState = (history.state ?? {}) as {
      documentNumber?: string;
      specialty?: string;
    };

    let documentNumber = navigationState.documentNumber;
    let specialty = navigationState.specialty;

    if (documentNumber) {
      this.persistUnscheduledContext(documentNumber, specialty ?? '');
    } else {
      const cached = this.readUnscheduledContext();
      documentNumber = cached?.documentNumber;
      specialty = cached?.specialty;
    }

    this.unscheduledSpecialty.set(specialty ?? '');

    if (!documentNumber) {
      this.isLoadingPatient.set(false);
      return;
    }
    this.loadPatientByDocument(documentNumber);
  }

  private persistUnscheduledContext(
    documentNumber: string,
    specialty: string
  ): void {
    sessionStorage.setItem(
      this.UNSCHEDULED_CONTEXT_KEY,
      JSON.stringify({ documentNumber, specialty })
    );
  }

  private readUnscheduledContext(): {
    documentNumber?: string;
    specialty?: string;
  } | null {
    const raw = sessionStorage.getItem(this.UNSCHEDULED_CONTEXT_KEY);
    if (!raw) return null;
    try {
      return JSON.parse(raw);
    } catch {
      return null;
    }
  }

  private clearUnscheduledContext(): void {
    sessionStorage.removeItem(this.UNSCHEDULED_CONTEXT_KEY);
  }

  private loadPatientByAppointment(idAppointment: string): void {
    this.doctorService.getPatientByAppointment(idAppointment).subscribe({
      next: (patient) => {
        this.patient.set(patient);
        this.isLoadingPatient.set(false);
        this.doctorService.loadMedicalRecordsByPatient(patient.id);
      },
      error: () => this.isLoadingPatient.set(false),
    });
  }

  private loadPatientByDocument(documentNumber: string): void {
    this.doctorService.getPatientByDocument(documentNumber).subscribe({
      next: (patient) => {
        this.patient.set(patient ?? undefined);
        this.isLoadingPatient.set(false);
        if (patient) this.doctorService.loadMedicalRecordsByPatient(patient.id);
      },
      error: () => this.isLoadingPatient.set(false),
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
    if (this.isScheduledContext()) {
      this.saveScheduledAttendance();
    } else {
      this.saveUnscheduledAttendance();
    }
  }

  private saveScheduledAttendance(): void {
    const idCita = this.idAppointment();
    if (!idCita) return;

    this.saveError.set('');
    this.isSaving.set(true);

    this.doctorService
      .updateAppointmentAsAttended(idCita, this.trimmedObservation())
      .subscribe({
        next: () => this.finishAndExit(),
        error: (err) => this.handleSaveError(err),
      });
  }

  private saveUnscheduledAttendance(): void {
    const p = this.patient();
    const specialty = this.unscheduledSpecialty();
    if (!p || !specialty) return;

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
      specialty,
      medicalCheckup: this.trimmedObservation(),
    };

    this.saveError.set('');
    this.isSaving.set(true);

    this.doctorService.registerUnscheduledAttention(request).subscribe({
      next: () => this.finishAndExit(),
      error: (err) => this.handleSaveError(err),
    });
  }

  private trimmedObservation(): string | null {
    return (
      this.newObservation().trim().slice(0, this.OBSERVATION_MAX_LENGTH) || null
    );
  }

  private finishAndExit(): void {
    this.allowNavigation = true;
    this.doctorService.resetMedicalRecords();
    if (!this.isScheduledContext()) {
      this.clearUnscheduledContext();
    }
    this.router.navigate(['/medico']);
  }

  private handleSaveError(err: { error?: { message?: string } }): void {
    this.isSaving.set(false);
    this.saveError.set(
      err?.error?.message || 'Ocurrió un error al guardar la historia clínica'
    );
  }
}
