import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  signal,
} from '@angular/core';
import {
  LucideCircleAlert,
  LucideCircleCheck,
  LucideFileText,
  LucideMail,
  LucidePhone,
  LucideShieldAlert,
  LucideUser,
  LucideX,
} from '@lucide/angular';
import { SearchInputComponent } from '../../../../designSystem/molecules/searchInput/searchInput.component';
import { AppError } from '../../../../shared/models/interfaces/apiError.model';
import { Patient } from '../../../../shared/models/interfaces/patient.model';
import { SystemPatient } from '../../../../shared/models/interfaces/systemPatient.model';
import { AdminService } from '../../service/admin.service';

const SEX_LABELS: Record<string, string> = {
  MASCULINO: 'Hombre',
  FEMENINO: 'Mujer',
};

const DOC_TYPE_LABELS: Record<string, string> = {
  CEDULA: 'CC',
  TARJETA_IDENTIDAD: 'TI',
  REGISTRO_NACIMIENTO: 'RC',
  PASAPORTE: 'PAS',
};

/** Acción pendiente de confirmación sobre la cuenta del paciente. */
type AccountAction = 'deactivate' | 'activate';

/**
 * Panel de administración de pacientes: búsqueda, consulta de perfil
 * completo y control de acceso (bloquear / reactivar la cuenta).
 *
 * La búsqueda (`GET /user/patients`) devuelve los datos básicos junto con
 * `accountEnabled`, que es la fuente de verdad del estado (Activo/Bloqueado).
 * El perfil completo (teléfono, correo, etc.) se pide aparte al seleccionar
 * al paciente. Al bloquear o reactivar, el estado se actualiza localmente
 * en la lista y en la selección para no repetir la búsqueda.
 */
@Component({
  selector: 'app-admin-patients',
  standalone: true,
  templateUrl: './adminPatients.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    SearchInputComponent,
    LucideUser,
    LucideFileText,
    LucidePhone,
    LucideMail,
    LucideCircleAlert,
    LucideCircleCheck,
    LucideShieldAlert,
    LucideX,
  ],
})
export class AdminPatientsComponent {
  private adminService = inject(AdminService);

  // ── Búsqueda ──────────────────────────────────────────────────────────────
  results = signal<SystemPatient[]>([]);
  loading = signal(false);
  searched = signal(false);

  // ── Selección + detalle ──────────────────────────────────────────────────
  selectedPatient = signal<SystemPatient | null>(null);
  patientDetail = signal<Patient | null>(null);
  loadingDetail = signal(false);
  detailError = signal<string | null>(null);

  // ── Control de acceso (bloquear / reactivar) ─────────────────────────────
  /** Acción esperando confirmación en el modal; `null` si el modal está cerrado. */
  pendingAction = signal<AccountAction | null>(null);
  updating = signal(false);
  actionError = signal<string | null>(null);

  readonly sexLabels = SEX_LABELS;
  readonly docTypeLabels = DOC_TYPE_LABELS;

  isBanned = computed(() => this.selectedPatient()?.accountEnabled === false);

  age = computed(() => {
    const detail = this.patientDetail();
    if (!detail?.birthDate) return null;
    return this.calculateAge(detail.birthDate);
  });

  // ── Búsqueda ──────────────────────────────────────────────────────────────
  onSearchChange(term: string): void {
    if (!term) {
      this.results.set([]);
      this.searched.set(false);
      return;
    }
    this.searched.set(true);
    this.loading.set(true);
    this.adminService.searchPatients(term).subscribe({
      next: (page) => {
        this.results.set(page.content);
        this.loading.set(false);
      },
      error: () => {
        this.results.set([]);
        this.loading.set(false);
      },
    });
  }

  // ── Selección ─────────────────────────────────────────────────────────────
  select(patient: SystemPatient): void {
    this.selectedPatient.set(patient);
    this.patientDetail.set(null);
    this.detailError.set(null);
    this.actionError.set(null);
    this.loadingDetail.set(true);

    this.adminService.getPatientDetail(patient.documentId).subscribe({
      next: (detail) => {
        this.patientDetail.set(detail);
        this.loadingDetail.set(false);
      },
      error: () => {
        this.detailError.set(
          'No se pudo cargar el detalle completo del paciente.'
        );
        this.loadingDetail.set(false);
      },
    });
  }

  clearSelection(): void {
    this.selectedPatient.set(null);
    this.patientDetail.set(null);
    this.detailError.set(null);
    this.pendingAction.set(null);
    this.actionError.set(null);
  }

  // ── Bloquear / reactivar ──────────────────────────────────────────────────
  openConfirm(action: AccountAction): void {
    this.actionError.set(null);
    this.pendingAction.set(action);
  }

  closeConfirm(): void {
    this.pendingAction.set(null);
  }

  confirmAccountChange(): void {
    const patient = this.selectedPatient();
    const action = this.pendingAction();
    if (!patient || !action) return;

    const enabling = action === 'activate';
    const request$ = enabling
      ? this.adminService.activatePatient(patient.id)
      : this.adminService.deactivatePatient(patient.id);

    this.updating.set(true);
    this.actionError.set(null);

    request$.subscribe({
      next: () => {
        this.setAccountEnabled(patient.id, enabling);
        this.updating.set(false);
        this.pendingAction.set(null);
      },
      error: (err: AppError) => {
        this.actionError.set(err.message);
        this.updating.set(false);
        this.pendingAction.set(null);
      },
    });
  }

  /** Refleja el nuevo estado de la cuenta en la lista de resultados y en la selección. */
  private setAccountEnabled(patientId: string, enabled: boolean): void {
    this.results.update((list) =>
      list.map((p) =>
        p.id === patientId ? { ...p, accountEnabled: enabled } : p
      )
    );
    this.selectedPatient.update((p) =>
      p && p.id === patientId ? { ...p, accountEnabled: enabled } : p
    );
  }

  // ── Helpers ───────────────────────────────────────────────────────────────
  initials(firstName: string, lastName: string): string {
    return `${firstName.charAt(0)}${lastName.charAt(0)}`.toUpperCase();
  }

  private calculateAge(birthDate: string): number {
    const birth = new Date(birthDate);
    const today = new Date();
    let age = today.getFullYear() - birth.getFullYear();
    const monthDiff = today.getMonth() - birth.getMonth();
    if (
      monthDiff < 0 ||
      (monthDiff === 0 && today.getDate() < birth.getDate())
    ) {
      age--;
    }
    return age;
  }
}
