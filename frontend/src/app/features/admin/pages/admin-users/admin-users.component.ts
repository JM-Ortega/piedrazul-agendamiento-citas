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
  LucideStethoscope,
  LucideUserPlus,
  LucideUsers,
} from '@lucide/angular';
import { ButtonComponent } from '../../../../design-system/atoms/button/button.component';
import { SystemUser } from '../../models/interfaces/system-user.model';
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
  templateUrl: './admin-users.component.html',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    LucideCalendar,
    LucideCreditCard,
    LucideStethoscope,
    LucideUserPlus,
    LucideUsers,
    ButtonComponent,
  ],
})
export class AdminUsersComponent implements OnInit {
  private adminService = inject(AdminService);
  private router = inject(Router);

  // ── State ─────────────────────────────────────────────────────────────────
  systemUsers = signal<SystemUser[]>([]);
  loading = signal(false);
  errorCarga = signal('');

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

  // ── Computed ──────────────────────────────────────────────────────────────
  doctors = computed(() =>
    this.systemUsers().filter(
      (u) =>
        u.roles.map((r) => r.toLowerCase()).includes('doctor') &&
        !u.roles.map((r) => r.toLowerCase()).includes('scheduler')
    )
  );

  schedulers = computed(() =>
    this.systemUsers().filter(
      (u) =>
        u.roles.map((r) => r.toLowerCase()).includes('scheduler') &&
        !u.roles.map((r) => r.toLowerCase()).includes('doctor')
    )
  );

  both = computed(() =>
    this.systemUsers().filter(
      (u) =>
        u.roles.map((r) => r.toLowerCase()).includes('doctor') &&
        u.roles.map((r) => r.toLowerCase()).includes('scheduler')
    )
  );

  // ── Lifecycle ─────────────────────────────────────────────────────────────
  ngOnInit(): void {
    this.loadUsers();
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
