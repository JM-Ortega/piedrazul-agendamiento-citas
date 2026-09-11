import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { Router } from '@angular/router';
import {
  LucideCalendar,
  LucideCreditCard,
  LucideInfo,
  LucideStethoscope,
  LucideUserPlus,
  LucideUsers,
} from '@lucide/angular';
import { ButtonComponent } from '../../../../design-system/atoms/button/button.component';
import { PaginationComponent } from '../../../../design-system/molecules/pagination/pagination.component';
import {
  SortControlComponent,
  SortDirection,
  SortOption,
} from '../../../../design-system/molecules/sortControl/sortControl.component';
import { PaginationMeta } from '../../../../shared/helpers/paginated-state';
import { AppError } from '../../../../shared/models/interfaces/api-error.model';
import { SystemUser } from '../../models/interfaces/systemUser.model';
import { AdminService } from '../../service/admin.service';

type UserType = 'both' | 'doctor' | 'scheduler';

interface UserStyle {
  card: string;
  iconBg: string;
  title: string;
  docIcon: string;
  icon: 'users' | 'stethoscope' | 'calendar';
}

@Component({
  selector: 'app-admin-users',
  templateUrl: './adminUsers.component.html',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    LucideCalendar,
    LucideCreditCard,
    LucideInfo,
    LucideStethoscope,
    LucideUserPlus,
    LucideUsers,
    ButtonComponent,
    PaginationComponent,
    SortControlComponent,
  ],
})
export class AdminUsersComponent implements OnInit {
  private adminService = inject(AdminService);
  private router = inject(Router);

  // ── Paginación ────────────────────────────────────────────────────────────
  readonly PAGE_SIZE = 6;

  // ── State: lista paginada (lo que se muestra en las cards) ────────────────
  systemUsers = signal<SystemUser[]>([]);
  pagination = signal<PaginationMeta | null>(null);
  loading = signal(false);
  errorCarga = signal('');

  // ── State: totales del sistema (para las estadísticas, independiente de la página) ──
  private allRoles = signal<string[][]>([]);
  loadingStats = signal(false);

  // ── Ordenamiento ──────────────────────────────────────────────────────────
  sortField = signal('lastName');
  sortDirection = signal<SortDirection>('asc');

  readonly sortOptions: SortOption[] = [
    { value: 'firstName', label: 'Nombre' },
    { value: 'lastName', label: 'Apellido' },
    { value: 'documentId', label: 'Documento' },
  ];

  // ── Estilos por tipo de usuario ──────────────────────────────────────────
  private readonly USER_STYLES: Record<UserType, UserStyle> = {
    both: {
      card: 'border-purple-100 bg-purple-50/30',
      iconBg: 'bg-[#7d3fc4]',
      title: 'text-purple-900',
      docIcon: 'text-purple-600',
      icon: 'users',
    },
    doctor: {
      card: 'border-[#a7c9ec] bg-[#d9e9f8]/30',
      iconBg: 'bg-[#215c98]',
      title: 'text-[#163c63]',
      docIcon: 'text-[#4e92d9]',
      icon: 'stethoscope',
    },
    scheduler: {
      card: 'border-green-100 bg-green-50/30',
      iconBg: 'bg-[#1fa36a]',
      title: 'text-green-900',
      docIcon: 'text-green-600',
      icon: 'calendar',
    },
  };

  // ── Computed de estadísticas (sobre TODOS los usuarios, no solo la página) ──
  doctorsCount = computed(
    () =>
      this.allRoles().filter(
        (roles) =>
          this.hasRole(roles, 'doctor') && !this.hasRole(roles, 'scheduler')
      ).length
  );

  schedulersCount = computed(
    () =>
      this.allRoles().filter(
        (roles) =>
          this.hasRole(roles, 'scheduler') && !this.hasRole(roles, 'doctor')
      ).length
  );

  bothCount = computed(
    () =>
      this.allRoles().filter(
        (roles) =>
          this.hasRole(roles, 'doctor') && this.hasRole(roles, 'scheduler')
      ).length
  );

  // ── Lifecycle ─────────────────────────────────────────────────────────────
  ngOnInit(): void {
    this.loadUsers();
    this.loadStats();
  }

  // ── Data loading: lista paginada ─────────────────────────────────────────
  loadUsers(pageNumber = 0): void {
    this.loading.set(true);
    this.errorCarga.set('');
    const sort = `${this.sortField()},${this.sortDirection()}`;
    this.adminService
      .getSystemUsers(pageNumber, this.PAGE_SIZE, sort)
      .subscribe({
        next: (page) => {
          this.systemUsers.set(page.content);
          this.pagination.set({
            pageNumber: page.pageNumber,
            pageSize: page.pageSize,
            totalElements: page.totalElements,
            totalPages: page.totalPages,
            first: page.first,
            last: page.last,
          });
          this.loading.set(false);
        },
        error: (err: AppError) => {
          this.errorCarga.set(err.message);
          this.loading.set(false);
        },
      });
  }

  /**
   * Carga los roles de TODOS los usuarios del sistema (una sola vez, en una
   * página grande) para calcular las estadísticas totales, independientemente
   * de qué página esté viendo el usuario en la lista.
   */
  private loadStats(): void {
    this.loadingStats.set(true);
    this.adminService.getSystemUsers(0, 1000).subscribe({
      next: (page) => {
        this.allRoles.set(page.content.map((u) => u.roles));
        this.loadingStats.set(false);
      },
      error: () => {
        this.loadingStats.set(false);
      },
    });
  }

  private hasRole(roles: string[], role: string): boolean {
    return roles.map((r) => r.toLowerCase()).includes(role);
  }

  onPageChange(pageNumber: number): void {
    this.loadUsers(pageNumber);
  }
  onSortFieldChange(field: string): void {
    this.sortField.set(field);
    this.loadUsers(0);
  }

  onSortDirectionChange(direction: SortDirection): void {
    this.sortDirection.set(direction);
    this.loadUsers(0);
  }
  navigateToCreate(): void {
    this.router.navigate(['/admin/usuarios/crear']);
  }

  // ── Helpers ───────────────────────────────────────────────────────────────
  roleLabel(role: string): string {
    return role.toUpperCase() === 'DOCTOR' ? 'Médico' : 'Agendador';
  }

  relevantRoles(user: SystemUser): string[] {
    return user.roles.filter((r) =>
      ['DOCTOR', 'SCHEDULER'].includes(r.toUpperCase())
    );
  }

  userStyle(user: SystemUser): UserStyle {
    const roles = user.roles.map((r) => r.toLowerCase());
    const type: UserType = roles.includes('doctor')
      ? roles.includes('scheduler')
        ? 'both'
        : 'doctor'
      : 'scheduler';
    return this.USER_STYLES[type];
  }
}
