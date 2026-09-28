import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import {
  LucideArrowLeft,
  LucideCalendar,
  LucideClipboardPen,
  LucideFolderOpen,
  LucidePencil,
  LucideSave,
  LucideTriangleAlert,
  LucideUser,
} from '@lucide/angular';
import { CanComponentDeactivate } from '../../../core/guards/canDeactivate.guard';
import { DoctorService } from '../../../core/services/doctor.service';
import { ButtonComponent } from '../../../designSystem/atoms/button/button.component';
import { TooltipDirective } from '../../../designSystem/atoms/tooltip/tooltip.directive';
import { PaginationComponent } from '../../../designSystem/molecules/pagination/pagination.component';
import {
  ToastComponent,
  ToastType,
} from '../../../designSystem/molecules/toastMessage/toast.component';
import { ConfirmModalComponent } from '../../../designSystem/organisms/confirmModal/confirmModal.component';
import { calcAge } from '../../../shared/helpers/patientValidation';
import {
  parseLocalDateString,
  toIsoDateString,
} from '../../../shared/helpers/transformDateLocal';
import { MedicalRecord } from '../../../shared/models/dtos/medicalRecord.dto';
import { UnscheduledAttention } from '../../../shared/models/dtos/unscheduledAttention.dto';
import { AppError } from '../../../shared/models/interfaces/apiError.model';
import { Patient } from '../../../shared/models/interfaces/patient.model';
import { FormatoPipe } from '../../../shared/pipes/formatoPipe';
import { PatientEditPanelComponent } from '../components/patientEditPanel/patientEditPanel.component';

type MedicalHistoryContext = 'scheduled' | 'unscheduled';

@Component({
  selector: 'app-doctor-medical-history',
  templateUrl: './medicalCheckUp.component.html',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    LucideTriangleAlert,
    LucideClipboardPen,
    LucideSave,
    LucideFolderOpen,
    LucideCalendar,
    LucideUser,
    LucidePencil,
    FormatoPipe,
    ButtonComponent,
    PaginationComponent,
    ConfirmModalComponent,
    LucideArrowLeft,
    TooltipDirective,
    ToastComponent,
    PatientEditPanelComponent,
  ],
})
export class DoctorMedicalHistoryComponent
  implements OnInit, CanComponentDeactivate
{
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  readonly doctorService = inject(DoctorService);
  private readonly today = toIsoDateString(new Date());

  // ── Constantes ────────────────────────────────────────────────────────────
  /** Longitud máxima permitida para la observación de la historia clínica. */
  readonly OBSERVATION_MAX_LENGTH = 300;
  private readonly UNSCHEDULED_CONTEXT_KEY =
    'doctor-unscheduled-attention-context';

  // ── Estado: contexto (cita programada vs. atención no programada) ──────────
  private readonly context = signal<MedicalHistoryContext>('scheduled');
  readonly isScheduledContext = computed(() => this.context() === 'scheduled');
  private readonly unscheduledSpecialty = signal<string>('');
  private readonly idAppointment = signal<string>('');

  /** True si se navegó en modo edición (?modo=editar) sobre una cita ya atendida. */
  private readonly editModeRequested = signal(false);
  readonly isEditMode = computed(
    () => this.isScheduledContext() && this.editModeRequested()
  );

  // ── Estado: paciente ─────────────────────────────────────────────────────
  readonly patient = signal<Patient | undefined>(undefined);
  readonly isLoadingPatient = signal(true);
  readonly mostrarInfo = signal(false);

  // ── Estado: edición de paciente ──────────────────────────────────────────
  readonly isEditingPatient = signal(false);
  /** Toast de éxito exclusivo del flujo de edición de paciente. */
  readonly toastMessage = signal('');
  readonly toastType = signal<ToastType | null>(null);
  private toastTimeout: ReturnType<typeof setTimeout> | null = null;

  // ── Estado: historial clínico ────────────────────────────────────────────
  readonly records = this.doctorService.medicalRecordsState.content;
  readonly medicalRecordsPagination =
    this.doctorService.medicalRecordsState.pagination;
  readonly isLoadingRecords = this.doctorService.isLoadingRecords;

  /** ID del registro clínico que se está editando (se oculta del historial). */
  readonly editingRecordId = signal<string | null>(null);
  private readonly editObservationInitialized = signal(false);

  /** Historial visible: excluye el registro que se está editando actualmente. */
  readonly visibleRecords = computed(() =>
    this.records().filter((r) => r.idClinicalHistory !== this.editingRecordId())
  );

  // ── Estado: observación / guardado de la atención ───────────────────────
  readonly newObservation = signal('');
  readonly remainingObservationChars = computed(
    () => this.OBSERVATION_MAX_LENGTH - this.newObservation().length
  );
  readonly saveError = signal('');
  readonly isSaving = signal(false);

  // ── Computed: datos derivados del paciente ───────────────────────────────
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

  // ── Estado: salida de la ruta (CanDeactivate) ────────────────────────────
  readonly showExitConfirmModal = signal(false);
  /** Se activa justo antes de navegar programáticamente tras guardar, para
   * no mostrar el modal de confirmación en ese caso. */
  private allowNavigation = false;
  private exitResolver: ((value: boolean) => void) | null = null;

  constructor() {
    effect(
      () => {
        const records = this.records();
        if (
          this.isEditMode() &&
          !this.editObservationInitialized() &&
          records.length > 0
        ) {
          const todaysRecord = this.findTodaysRecord(records);
          if (!todaysRecord) return;

          this.editingRecordId.set(todaysRecord.idClinicalHistory);
          this.newObservation.set(
            todaysRecord.description.slice(0, this.OBSERVATION_MAX_LENGTH)
          );
          this.editObservationInitialized.set(true);
        }
      },
      { allowSignalWrites: true }
    );
  }

  // ── Lifecycle ─────────────────────────────────────────────────────────────
  ngOnInit(): void {
    this.editModeRequested.set(
      this.route.snapshot.queryParamMap.get('modo') === 'editar'
    );

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

  // ── UI: sección de información del paciente ──────────────────────────────
  toggleInfo(): void {
    this.mostrarInfo.update((v) => !v);
  }

  // ── Contexto no programado (persistencia en sessionStorage) ──────────────
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

  // ── Carga de datos del paciente ───────────────────────────────────────────
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

  // ── Historial clínico ─────────────────────────────────────────────────────
  /** Cambia de página el historial clínico del paciente actual. */
  onMedicalRecordsPageChange(page: number): void {
    const patientId = this.patient()?.id;
    if (patientId) {
      this.doctorService.loadMedicalRecordsByPatient(patientId, page);
    }
  }

  /** Busca, en una página de registros, el correspondiente a la fecha de hoy. */
  private findTodaysRecord(
    records: MedicalRecord[]
  ): MedicalRecord | undefined {
    return records.find((r) => r.attendedAt?.slice(0, 10) === this.today);
  }

  // ── Observación clínica ───────────────────────────────────────────────────
  /** Trunca la observación al límite permitido para evitar pegar texto largo. */
  onObservationChange(value: string): void {
    this.newObservation.set(value.slice(0, this.OBSERVATION_MAX_LENGTH));
  }

  private trimmedObservation(): string | null {
    return (
      this.newObservation().trim().slice(0, this.OBSERVATION_MAX_LENGTH) || null
    );
  }

  // ── Guardado de la atención ───────────────────────────────────────────────
  confirmAttendanceAndExit(): void {
    if (this.isEditMode()) {
      this.saveEditedObservation();
      return;
    }
    if (this.isScheduledContext()) {
      this.saveScheduledAttendance();
    } else {
      this.saveUnscheduledAttendance();
    }
  }

  /** Actualiza la observación de un control médico ya existente. */
  private saveEditedObservation(): void {
    const idCheckUp = this.editingRecordId();
    if (!idCheckUp) return;

    this.saveError.set('');
    this.isSaving.set(true);

    this.doctorService
      .updateCheckup(idCheckUp, this.trimmedObservation())
      .subscribe({
        next: () => this.finishAndExit(),
        error: (err: AppError) => this.handleSaveError(err),
      });
  }

  /** Marca la cita programada como atendida. */
  private saveScheduledAttendance(): void {
    const idCita = this.idAppointment();
    if (!idCita) return;

    this.saveError.set('');
    this.isSaving.set(true);

    this.doctorService
      .updateAppointmentAsAttended(idCita, this.trimmedObservation())
      .subscribe({
        next: () => this.finishAndExit(),
        error: (err: AppError) => this.handleSaveError(err),
      });
  }

  /** Registra la atención de un paciente sin cita previa. */
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
      error: (err: AppError) => this.handleSaveError(err),
    });
  }

  /** Limpia el estado local y vuelve a la lista de citas del día. */
  private finishAndExit(): void {
    this.allowNavigation = true;
    this.doctorService.resetMedicalRecords();
    if (!this.isScheduledContext()) {
      this.clearUnscheduledContext();
    }
    this.router.navigate(['/medico']);
  }

  /** Muestra el mensaje real del backend (vía `AppError.message`) en el banner inline. */
  private handleSaveError(err: AppError): void {
    this.isSaving.set(false);
    this.saveError.set(err.message);
  }

  // ── Edición de datos del paciente ───────────────────────────────────────
  startEditPatient(): void {
    this.isEditingPatient.set(true);
  }

  /** Descarta la edición sin guardar y vuelve a la vista de solo lectura. */
  cancelEditPatient(): void {
    this.isEditingPatient.set(false);
  }

  /** Recibe el paciente ya actualizado por el backend y muestra el toast de éxito. */
  onPatientSaved(updated: Patient): void {
    this.patient.set(updated);
    this.isEditingPatient.set(false);
    this.showToast('Datos del paciente actualizados correctamente.', 'success');
  }

  /** Muestra el toast (solo usado por la edición de paciente) y lo oculta a los 3.5s. */
  private showToast(message: string, type: ToastType): void {
    if (this.toastTimeout) clearTimeout(this.toastTimeout);
    this.toastMessage.set(message);
    this.toastType.set(type);
    this.toastTimeout = setTimeout(() => {
      this.toastMessage.set('');
      this.toastType.set(null);
    }, 3500);
  }

  // ── Salida de la ruta (CanDeactivate) ────────────────────────────────────
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

  // ── Navegación ────────────────────────────────────────────────────────────
  /** Vuelve a las citas de hoy del doctor, respetando el guard de cambios sin guardar. */
  goBack(): void {
    this.router.navigate(['/medico']);
  }
}
