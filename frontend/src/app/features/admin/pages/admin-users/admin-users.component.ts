import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { Router } from '@angular/router';
import {
  Calendar,
  Clock,
  CreditCard,
  LucideAngularModule,
  Stethoscope,
  UserPlus,
  Users,
} from 'lucide-angular';

import { SystemUser } from '../../models/interfaces/system-user.model';
import { AdminService } from '../../service/admin.service';
@Component({
  selector: 'app-admin-users',
  templateUrl: './admin-users.component.html',
  standalone: true,
  imports: [LucideAngularModule],
})
export class AdminUsersComponent implements OnInit {
  private adminService = inject(AdminService);
  private router = inject(Router);

  private readonly specialtyLabels: Record<string, string> = {
    FISIOTERAPIA: 'Fisioterapia',
    TERAPIA_NEURAL: 'Terapia Neural',
    QUIROPRAXIA: 'Quiropraxia',
  };

  specialtyLabel(specialty: string): string {
    const clean = specialty.replace(/^\[|\]$/g, '').trim();
    return this.specialtyLabels[clean] ?? clean;
  }

  readonly Users = Users;
  readonly UserPlus = UserPlus;
  readonly Stethoscope = Stethoscope;
  readonly Calendar = Calendar;
  readonly CreditCard = CreditCard;
  readonly Clock = Clock;

  // ── State ─────────────────────────────────────────────────────────────────
  systemUsers = signal<SystemUser[]>([]);
  loading = signal(false);
  errorCarga = signal('');

  // ── Computed ──────────────────────────────────────────────────────────────
  doctors = computed(() =>
    this.systemUsers().filter(
      (u) => u.roles.includes('doctor') && !u.roles.includes('scheduler'),
    ),
  );
  schedulers = computed(() =>
    this.systemUsers().filter(
      (u) => u.roles.includes('scheduler') && !u.roles.includes('doctor'),
    ),
  );
  both = computed(() =>
    this.systemUsers().filter(
      (u) => u.roles.includes('doctor') && u.roles.includes('scheduler'),
    ),
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

  // ── Helpers de lógica ─────────────────────────────────────────────────────
  hasBothRoles(user: SystemUser): boolean {
    return user.roles.includes('doctor') && user.roles.includes('scheduler');
  }

  roleLabel(role: string): string {
    return role === 'doctor' ? 'Médico' : 'Agendador';
  }
}
