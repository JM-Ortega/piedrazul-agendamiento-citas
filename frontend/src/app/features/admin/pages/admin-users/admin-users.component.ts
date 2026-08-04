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
}
