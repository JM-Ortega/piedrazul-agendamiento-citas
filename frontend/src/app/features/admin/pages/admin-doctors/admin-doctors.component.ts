import { Component, HostListener, inject, OnInit, signal } from '@angular/core';
import { Router } from '@angular/router';
import {
  Activity,
  ArrowLeft,
  Bone,
  Calendar,
  Check,
  CreditCard,
  Edit3,
  Heart,
  LucideAngularModule,
  Save,
  Stethoscope,
  X,
  Zap,
} from 'lucide-angular';
import { forkJoin, Observable } from 'rxjs';
import { DoctorAdminDto } from '../../models/dtos/DoctorAdminDto';
import { AdminService } from '../../service/admin.service';

@Component({
  selector: 'app-admin-doctors',
  templateUrl: './admin-doctors.component.html',
  standalone: true,
  imports: [LucideAngularModule],
})
export class AdminDoctorsComponent implements OnInit {
  private adminService = inject(AdminService);
  public router = inject(Router);

  readonly ArrowLeft = ArrowLeft;
  readonly Stethoscope = Stethoscope;
  readonly Edit3 = Edit3;
  readonly X = X;
  readonly Save = Save;
  readonly Check = Check;
  readonly Calendar = Calendar;
  readonly CreditCard = CreditCard;
  readonly Heart = Heart;
  readonly Bone = Bone;
  readonly Activity = Activity;
  readonly Zap = Zap;

  readonly specialtiesList = [
    {
      name: 'Medicina General',
      value: 'MEDICINA_GENERAL',
      icon: Heart,
      color: 'text-red-600',
    },
    {
      name: 'Quiropraxia',
      value: 'QUIROPRAXIA',
      icon: Bone,
      color: 'text-orange-600',
    },
    {
      name: 'Fisioterapia',
      value: 'FISIOTERAPIA',
      icon: Activity,
      color: 'text-green-600',
    },
    {
      name: 'Terapia Neural',
      value: 'TERAPIA_NEURAL',
      icon: Zap,
      color: 'text-purple-600',
    },
  ];
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
    console.log('specialties del doctor:', doctor.specialties);
    console.log(
      'values en specialtiesList:',
      this.specialtiesList.map((s) => s.value)
    );
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

    const toAdd = newSpecialties.filter((s) => !currentSpecialties.includes(s));
    const toRemove = currentSpecialties.filter(
      (s) => !newSpecialties.includes(s)
    );

    const hadScheduler = doctor.roles
      .map((r) => r.toUpperCase())
      .includes('SCHEDULER');
    const wantsScheduler = this.editingHasScheduler();

    const calls: Observable<void>[] = [];

    if (toAdd.length > 0)
      calls.push(this.adminService.addSpecialties(doctor.id, toAdd));
    if (toRemove.length > 0)
      calls.push(this.adminService.removeSpecialties(doctor.id, toRemove));
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
      next: () => {
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
        this.savingDoctorId.set(null);
        this.handleCancel();
      },
      error: () => {
        this.savingDoctorId.set(null);
      },
    });
  }

  toggleSpecialty(name: string): void {
    this.editingSpecialties.update((prev) =>
      prev.includes(name) ? prev.filter((s) => s !== name) : [...prev, name]
    );
  }

  // ── Helpers ───────────────────────────────────────────────────────────────
  isEditing(doctorId: string): boolean {
    return this.editingDoctorId() === doctorId;
  }

  hasScheduler(doctor: DoctorAdminDto): boolean {
    return doctor.roles.includes('SCHEDULER');
  }

  getSpecialtyIcon(name: string) {
    return (
      this.specialtiesList.find((s) => s.value === name)?.icon || Stethoscope
    );
  }

  getSpecialtyColor(name: string): string {
    return (
      this.specialtiesList.find((s) => s.value === name)?.color ||
      'text-blue-600'
    );
  }
}
