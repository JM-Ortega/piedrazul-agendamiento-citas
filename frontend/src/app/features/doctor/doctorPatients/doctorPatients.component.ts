import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  OnDestroy,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { LucideTriangleAlert, LucideUserSearch } from '@lucide/angular';
import {
  catchError,
  distinctUntilChanged,
  map,
  of,
  Subscription,
  switchMap,
  tap,
} from 'rxjs';
import { DoctorService } from '../../../core/services/doctor.service';
import {
  ToastComponent,
  ToastType,
} from '../../../designSystem/molecules/toastMessage/toast.component';
import { PatientQuickSearchComponent } from '../../../designSystem/organisms/patientQuickSearch/patientQuickSearch.component';
import { PaginatedState } from '../../../shared/helpers/paginatedState';
import { AppointmentsPatient } from '../../../shared/models/dtos/appointments.dto';
import { PatientQuickResult } from '../../../shared/models/dtos/patientQuickResult.dto';
import { AppError } from '../../../shared/models/interfaces/apiError.model';
import { Patient } from '../../../shared/models/interfaces/patient.model';
import { ClinicalHistoryListComponent } from '../components/clinicalHistoryList/clinicalHistoryList.component';
import { PatientAppointmentsTableComponent } from '../components/patientAppointmentsTable/patientAppointmentsTable.component';
import { PatientInfoCardComponent } from '../components/patientInfoCard/patientInfoCard.component';

/** Query param que guarda el paciente seleccionado (sobrevive a recargas). */
const PATIENT_QUERY_PARAM = 'paciente';

/**
 * Vista "Pacientes" del médico. Permite buscar un paciente y, al
 * seleccionarlo, ver y editar su información, las citas que ha tenido con
 * el médico autenticado y su historial de consultas.
 *
 * El paciente seleccionado vive en la URL (`?paciente=<id>`): seleccionar o
 * quitar un paciente solo cambia ese parámetro, y un único flujo reactivo
 * se encarga de cargar los datos (cancelando lo anterior si el usuario
 * cambia de paciente antes de que termine la carga).
 */
@Component({
  selector: 'app-doctor-patients',
  templateUrl: './doctorPatients.component.html',
  styleUrl: './doctorPatients.component.css',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    LucideTriangleAlert,
    LucideUserSearch,
    ToastComponent,
    PatientQuickSearchComponent,
    PatientInfoCardComponent,
    PatientAppointmentsTableComponent,
    ClinicalHistoryListComponent,
  ],
})
export class DoctorPatientsComponent implements OnDestroy {
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private doctorService = inject(DoctorService);

  readonly APPOINTMENTS_PAGE_SIZE = 5;

  /** Tarjeta del paciente (para saber si se está editando). */
  private readonly infoCard = viewChild(PatientInfoCardComponent);

  // ── Estado: paciente ─────────────────────────────────────────────────────
  readonly patient = signal<Patient | null>(null);
  readonly isLoadingPatient = signal(false);
  readonly patientError = signal('');

  /** Paciente en el formato que espera el buscador (chip de seleccionado). */
  readonly selectedPatient = computed<PatientQuickResult | null>(() => {
    const p = this.patient();
    return p
      ? {
          id: p.id,
          identification: p.identification,
          firstName: p.firstName,
          lastName: p.lastName,
        }
      : null;
  });

  /** True mientras la tarjeta del paciente está en modo edición. */
  readonly isEditingPatient = computed(
    () => this.infoCard()?.isEditingPatient() ?? false
  );

  // ── Estado: citas del paciente con el médico ─────────────────────────────
  private readonly appointmentsState =
    new PaginatedState<AppointmentsPatient>();
  readonly appointments = this.appointmentsState.content;
  readonly appointmentsPagination = this.appointmentsState.pagination;
  readonly isLoadingAppointments = signal(false);
  readonly appointmentsError = signal('');
  private appointmentsRequest: Subscription | null = null;

  // ── Estado: historial clínico (compartido con control médico) ────────────
  readonly records = this.doctorService.medicalRecordsState.content;
  readonly recordsPagination =
    this.doctorService.medicalRecordsState.pagination;
  readonly isLoadingRecords = this.doctorService.isLoadingRecords;

  // ── Estado: toast ────────────────────────────────────────────────────────
  readonly toastMessage = signal('');
  readonly toastType = signal<ToastType | null>(null);
  private toastTimeout: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    this.route.queryParamMap
      .pipe(
        map((params) => params.get(PATIENT_QUERY_PARAM)),
        distinctUntilChanged(),
        tap((patientId) => this.resetSelection(!!patientId)),
        switchMap((patientId) =>
          patientId
            ? this.doctorService.getPatientById(patientId).pipe(
                catchError((err: AppError) => {
                  this.patientError.set(err.message);
                  return of(null);
                })
              )
            : of(null)
        ),
        takeUntilDestroyed()
      )
      .subscribe((patient) => {
        this.isLoadingPatient.set(false);
        this.patient.set(patient);
        if (patient) {
          this.loadAppointments(0);
          this.doctorService.loadMedicalRecordsByPatient(patient.id);
        }
      });
  }

  ngOnDestroy(): void {
    this.appointmentsRequest?.unsubscribe();
    this.doctorService.resetMedicalRecords();
    if (this.toastTimeout) clearTimeout(this.toastTimeout);
  }

  // ── Selección de paciente ────────────────────────────────────────────────
  onPatientSelected(patient: PatientQuickResult): void {
    this.setPatientParam(patient.id);
  }

  onClearPatient(): void {
    if (this.isEditingPatient()) return;
    this.setPatientParam(null);
  }

  private setPatientParam(patientId: string | null): void {
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { [PATIENT_QUERY_PARAM]: patientId },
    });
  }

  /** Limpia todo lo del paciente anterior antes de cargar uno nuevo. */
  private resetSelection(willLoad: boolean): void {
    this.patient.set(null);
    this.patientError.set('');
    this.isLoadingPatient.set(willLoad);

    this.appointmentsRequest?.unsubscribe();
    this.appointmentsRequest = null;
    this.appointmentsState.clear();
    this.appointmentsError.set('');
    this.isLoadingAppointments.set(false);

    this.doctorService.resetMedicalRecords();
  }

  // ── Citas ────────────────────────────────────────────────────────────────
  /**
   * Carga una página de las citas del paciente con el médico autenticado,
   * de la más reciente a la más antigua. Si hay otra carga en curso, la
   * cancela.
   */
  private loadAppointments(pageNumber: number): void {
    const patient = this.patient();
    if (!patient) return;

    this.appointmentsRequest?.unsubscribe();
    this.appointmentsError.set('');
    this.isLoadingAppointments.set(true);

    this.appointmentsRequest = this.doctorService
      .getMe()
      .pipe(
        switchMap((doctor) =>
          this.doctorService.getAppointmentsByDoctor(
            doctor.id,
            pageNumber,
            this.APPOINTMENTS_PAGE_SIZE,
            { patientId: patient.id, sortDirection: 'DESC' }
          )
        )
      )
      .subscribe({
        next: (response) => {
          this.appointmentsState.set(response);
          this.isLoadingAppointments.set(false);
        },
        error: (err: AppError) => {
          this.appointmentsState.clear();
          this.appointmentsError.set(err.message);
          this.isLoadingAppointments.set(false);
        },
      });
  }

  onAppointmentsPageChange(pageNumber: number): void {
    this.loadAppointments(pageNumber);
  }

  // ── Historial clínico ────────────────────────────────────────────────────
  onRecordsPageChange(pageNumber: number): void {
    const patientId = this.patient()?.id;
    if (patientId) {
      this.doctorService.loadMedicalRecordsByPatient(patientId, pageNumber);
    }
  }

  // ── Edición de datos del paciente ────────────────────────────────────────
  /** Recibe el paciente ya actualizado por el backend y muestra el toast de éxito. */
  onPatientSaved(updated: Patient): void {
    this.patient.set(updated);
    this.showToast('Datos del paciente actualizados correctamente.', 'success');
  }

  /** Muestra el toast y lo oculta a los 3.5s (mismo patrón que control médico). */
  private showToast(message: string, type: ToastType): void {
    if (this.toastTimeout) clearTimeout(this.toastTimeout);
    this.toastMessage.set(message);
    this.toastType.set(type);
    this.toastTimeout = setTimeout(() => {
      this.toastMessage.set('');
      this.toastType.set(null);
    }, 3500);
  }
}
