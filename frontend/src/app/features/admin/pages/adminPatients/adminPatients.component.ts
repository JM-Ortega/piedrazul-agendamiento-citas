import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  signal,
} from '@angular/core';
import {
  LucideAlertCircle,
  LucideFileText,
  LucideMail,
  LucidePhone,
  LucideShieldAlert,
  LucideUser,
  LucideX,
} from '@lucide/angular';
import { SearchInputComponent } from '../../../../designSystem/molecules/searchInput/searchInput.component';
import { PatientQuickResult } from '../../../../shared/models/dtos/patientQuickResult.dto';
import { Patient } from '../../../../shared/models/interfaces/patient.model';
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

/**
 * Panel de administración de pacientes: búsqueda, consulta de perfil
 * completo y control de acceso (banear/bloquear).
 *
 * El endpoint de baneo aún no existe en el backend (ver
 * `AdminService.banPatient`), y `Patient` todavía no tiene un campo de
 * estado (activo/bloqueado). Por eso el estado que se muestra en pantalla
 * (`bannedPatientIds`) es solo memoria de esta sesión del navegador — no
 * persiste al recargar ni viene del servidor. En cuanto el backend entregue
 * el endpoint real y (probablemente) un campo de estado en `Patient`, hay
 * que reemplazar `bannedPatientIds`/`isBanned` por ese dato real.
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
    LucideAlertCircle,
    LucideShieldAlert,
    LucideX,
  ],
})
export class AdminPatientsComponent {
  private adminService = inject(AdminService);

  // ── Búsqueda ──────────────────────────────────────────────────────────────
  results = signal<PatientQuickResult[]>([]);
  loading = signal(false);
  searched = signal(false);

  // ── Selección + detalle ──────────────────────────────────────────────────
  selectedPatient = signal<PatientQuickResult | null>(null);
  patientDetail = signal<Patient | null>(null);
  loadingDetail = signal(false);
  detailError = signal<string | null>(null);

  // ── Baneo (ver nota de clase: pendiente de backend) ───────────────────────
  bannedPatientIds = signal<Set<string>>(new Set());
  showBanConfirm = signal(false);
  banning = signal(false);
  banError = signal<string | null>(null);

  readonly sexLabels = SEX_LABELS;
  readonly docTypeLabels = DOC_TYPE_LABELS;

  isBanned = computed(() => {
    const p = this.selectedPatient();
    return p ? this.bannedPatientIds().has(p.id) : false;
  });

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
  select(patient: PatientQuickResult): void {
    this.selectedPatient.set(patient);
    this.patientDetail.set(null);
    this.detailError.set(null);
    this.banError.set(null);
    this.loadingDetail.set(true);

    this.adminService.getPatientDetail(patient.identification).subscribe({
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
    this.showBanConfirm.set(false);
    this.banError.set(null);
  }

  // ── Baneo ─────────────────────────────────────────────────────────────────
  openBanConfirm(): void {
    this.banError.set(null);
    this.showBanConfirm.set(true);
  }

  closeBanConfirm(): void {
    this.showBanConfirm.set(false);
  }

  confirmBan(): void {
    const patient = this.selectedPatient();
    if (!patient) return;

    this.banning.set(true);
    this.banError.set(null);

    this.adminService.banPatient(patient.id).subscribe({
      next: () => {
        this.bannedPatientIds.update((set) => new Set(set).add(patient.id));
        this.banning.set(false);
        this.showBanConfirm.set(false);
      },
      error: () => {
        this.banError.set(
          'No se pudo completar la acción: el backend todavía no expone este endpoint.'
        );
        this.banning.set(false);
      },
    });
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
