import {
  ChangeDetectionStrategy,
  Component,
  HostListener,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { Router } from '@angular/router';
import {
  LucideCalendar,
  LucideCheck,
  LucideDynamicIcon,
  LucideEdit3,
  LucideSave,
  LucideStethoscope,
} from '@lucide/angular';
import { forkJoin, Observable } from 'rxjs';
import { AppService } from '../../../../core/services/app.service';
import { ButtonComponent } from '../../../../design-system/atoms/button/button.component';
import { toggleInArray } from '../../../../shared/helpers/array-utils';
import {
  getAllSpecialtiesMeta,
  getSpecialtyMeta,
} from '../../../../shared/helpers/specialty-catalog';
import { AppError } from '../../../../shared/models/interfaces/api-error.model';
import { DoctorAdminDto } from '../../models/dtos/DoctorAdminDto';
import { AdminService } from '../../service/admin.service';
@Component({
  selector: 'app-admin-doctors',
  templateUrl: './admin-doctors.component.html',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    LucideCalendar,
    LucideCheck,
    LucideEdit3,
    LucideSave,
    LucideStethoscope,
    LucideDynamicIcon,
    ButtonComponent,
  ],
})
export class AdminDoctorsComponent implements OnInit {
  private adminService = inject(AdminService);
  public router = inject(Router);
  private appService = inject(AppService);

  readonly specialtiesList = getAllSpecialtiesMeta();
  // ── State ─────────────────────────────────────────────────────────────────
  doctors = signal<DoctorAdminDto[]>([]);
  loading = signal(false);
  errorCarga = signal('');
  editingDoctorId = signal<string | null>(null);
  editingSpecialties = signal<string[]>([]);
  editingHasScheduler = signal(false);
  savingDoctorId = signal<string | null>(null);
  hoveredDoctorId = signal<string | null>(null);

  // ── Lifecycle ─────────────────────────────────────────────────────────────
  ngOnInit(): void {
    this.loadDoctors();
  }

  @HostListener('document:mousedown', ['$event'])
  onDocumentClick(event: MouseEvent): void {
    const target = event.target as HTMLElement;
    if (!target.closest('.edit-container')) {
      this.handleCancel();
    }
  }

  // ── Data ──────────────────────────────────────────────────────────────────
  loadDoctors(): void {
    this.loading.set(true);
    this.errorCarga.set('');
    this.adminService.getDoctorsAdmin().subscribe({
      next: (data) => {
        this.doctors.set(data);
        this.loading.set(false);
      },
      error: () => {
        this.errorCarga.set('Error al cargar los médicos. Intente de nuevo.');
        this.loading.set(false);
      },
    });
  }

  // ── Edit ──────────────────────────────────────────────────────────────────
  handleEdit(doctor: DoctorAdminDto): void {
    this.editingDoctorId.set(doctor.id);
    this.editingSpecialties.set([...doctor.specialties]);
    this.editingHasScheduler.set(doctor.roles.includes('SCHEDULER'));
  }

  handleCancel(): void {
    this.editingDoctorId.set(null);
    this.editingSpecialties.set([]);
    this.editingHasScheduler.set(false);
  }

  handleSave(doctor: DoctorAdminDto): void {
    if (this.editingSpecialties().length === 0) {
      alert('Debe seleccionar al menos una especialidad');
      return;
    }

    this.savingDoctorId.set(doctor.id);

    const currentSpecialties = doctor.specialties;
    const newSpecialties = this.editingSpecialties();

    const specialtiesChanged =
      newSpecialties.length !== currentSpecialties.length ||
      newSpecialties.some((s) => !currentSpecialties.includes(s));

    const hadScheduler = doctor.roles
      .map((r) => r.toUpperCase())
      .includes('SCHEDULER');
    const wantsScheduler = this.editingHasScheduler();

    const calls: Observable<void>[] = [];

    if (specialtiesChanged)
      calls.push(
        this.adminService.changeSpecialties(doctor.id, newSpecialties)
      );
    if (!hadScheduler && wantsScheduler)
      calls.push(this.adminService.giveDoctorSchedulerRole(doctor.documentId));
    if (hadScheduler && !wantsScheduler)
      calls.push(
        this.adminService.revokeDoctorSchedulerRole(doctor.documentId)
      );

    if (calls.length === 0) {
      this.savingDoctorId.set(null);
      this.handleCancel();
      return;
    }
    forkJoin(calls).subscribe({
      next: async () => {
        this.doctors.update((list) =>
          list.map((d) => {
            if (d.id !== doctor.id) return d;
            const updatedRoles = wantsScheduler
              ? [...new Set([...d.roles, 'SCHEDULER'])]
              : d.roles.filter((r) => r.toUpperCase() !== 'SCHEDULER');
            return {
              ...d,
              specialties: newSpecialties,
              roles: updatedRoles as DoctorAdminDto['roles'],
            };
          })
        );

        // Refresh del token solo si cambió el rol
        const roleChanged = hadScheduler !== wantsScheduler;
        if (roleChanged) {
          await this.appService.refreshRoles();
        }

        this.savingDoctorId.set(null);
        this.handleCancel();
      },
      error: (err: AppError) => {
        this.errorCarga.set(err.message);
        this.loading.set(false);
      },
    });
  }

  toggleSpecialty(name: string): void {
    this.editingSpecialties.update((prev) => toggleInArray(prev, name));
  }

  // ── Helpers ───────────────────────────────────────────────────────────────
  isEditing(doctorId: string): boolean {
    return this.editingDoctorId() === doctorId;
  }

  hasScheduler(doctor: DoctorAdminDto): boolean {
    return doctor.roles.includes('SCHEDULER');
  }

  getSpecialtyIcon(name: string) {
    return getSpecialtyMeta(name).icon;
  }

  getSpecialtyColor(name: string): string {
    return getSpecialtyMeta(name).color;
  }

  getSpecialtyName(value: string): string {
    return getSpecialtyMeta(value).label;
  }
}
