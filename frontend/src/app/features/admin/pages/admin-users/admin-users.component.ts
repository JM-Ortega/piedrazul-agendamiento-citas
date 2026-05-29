import {
  Component,
  computed,
  ElementRef,
  HostListener,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { Router } from '@angular/router';
import {
  Calendar,
  Check,
  Clock,
  CreditCard,
  Edit3,
  LucideAngularModule,
  Stethoscope,
  UserPlus,
  Users,
  X,
} from 'lucide-angular';
import { AppService } from '../../../../core/services/app.service';
import { FormatoPipe } from '../../../../shared/pipes/formatoPipe';
import { SystemUser } from '../../models/interfaces/system-user.model';
import { AdminService } from '../../service/admin.service';
@Component({
  selector: 'app-admin-users',
  templateUrl: './admin-users.component.html',
  standalone: true,
  imports: [LucideAngularModule, FormatoPipe],
})
export class AdminUsersComponent implements OnInit {
  private adminService = inject(AdminService);
  private router = inject(Router);
  private el = inject(ElementRef);
  private appService = inject(AppService);
  readonly Users = Users;
  readonly UserPlus = UserPlus;
  readonly Stethoscope = Stethoscope;
  readonly Calendar = Calendar;
  readonly CreditCard = CreditCard;
  readonly Clock = Clock;
  readonly Edit3 = Edit3;
  readonly X = X;
  readonly Check = Check;

  // ── State ─────────────────────────────────────────────────────────────────
  systemUsers = signal<SystemUser[]>([]);
  loading = signal(false);
  errorCarga = signal('');
  editingUserId = signal<string | null>(null);
  hoveredUserId = signal<string | null>(null);
  togglingUserId = signal<string | null>(null);

  // ── Computed ──────────────────────────────────────────────────────────────
  doctors = computed(() =>
    this.systemUsers().filter(
      (u) =>
        u.roles.map((r) => r.toLowerCase()).includes('doctor') &&
        !u.roles.map((r) => r.toLowerCase()).includes('scheduler'),
    ),
  );

  schedulers = computed(() =>
    this.systemUsers().filter(
      (u) =>
        u.roles.map((r) => r.toLowerCase()).includes('scheduler') &&
        !u.roles.map((r) => r.toLowerCase()).includes('doctor'),
    ),
  );

  both = computed(() =>
    this.systemUsers().filter(
      (u) =>
        u.roles.map((r) => r.toLowerCase()).includes('doctor') &&
        u.roles.map((r) => r.toLowerCase()).includes('scheduler'),
    ),
  );

  // ── Lifecycle ─────────────────────────────────────────────────────────────
  ngOnInit(): void {
    this.loadUsers();
  }

  // ── Click fuera del popover ───────────────────────────────────────────────
  @HostListener('document:mousedown', ['$event'])
  onDocumentClick(event: MouseEvent): void {
    const target = event.target as HTMLElement;
    if (!target.closest('.popover-container')) {
      this.editingUserId.set(null);
    }
  }

  // ── Data loading ──────────────────────────────────────────────────────────
  loadUsers(): void {
    this.loading.set(true);
    this.errorCarga.set('');
    this.adminService.getSystemUsers().subscribe({
      next: (users) => {
        this.systemUsers.set(users);
        this.loading.set(false);
      },
      error: () => {
        this.errorCarga.set('Error al cargar los usuarios. Intente de nuevo.');
        this.loading.set(false);
      },
    });
  }

  navigateToCreate(): void {
    this.router.navigate(['/admin/usuarios/crear']);
  }

  // ── Popover ───────────────────────────────────────────────────────────────
  togglePopover(userId: string): void {
    this.editingUserId.set(this.editingUserId() === userId ? null : userId);
  }

  // ── Toggle scheduler role ─────────────────────────────────────────────────

  toggleSchedulerRole(user: SystemUser): void {
    if (!user.documentId) return;

    const hasScheduler = user.roles
      .map((r) => r.toLowerCase())
      .includes('scheduler');

    this.togglingUserId.set(user.id);
    this.editingUserId.set(null);

    const call$ = hasScheduler
      ? this.adminService.revokeDoctorSchedulerRole(user.documentId)
      : this.adminService.giveDoctorSchedulerRole(user.documentId);

    call$.subscribe({
      next: () => {
        this.togglingUserId.set(null);
        this.systemUsers.update((users) =>
          users.map((u) => {
            if (u.id !== user.id) return u;
            const updatedRoles = (
              hasScheduler
                ? u.roles.filter((r) => r.toUpperCase() !== 'SCHEDULER')
                : [...u.roles, 'SCHEDULER']
            ) as ('doctor' | 'scheduler')[];
            return { ...u, roles: updatedRoles };
          }),
        );
        this.appService.refreshRoles();
      },
      error: () => {
        this.togglingUserId.set(null);
      },
    });
  }

  // ── Helpers ───────────────────────────────────────────────────────────────
  roleLabel(role: string): string {
    return role.toUpperCase() === 'DOCTOR' ? 'Médico' : 'Agendador';
  }

  hasSchedulerRole(user: SystemUser): boolean {
    return user.roles.map((r) => r.toLowerCase()).includes('scheduler');
  }
}
