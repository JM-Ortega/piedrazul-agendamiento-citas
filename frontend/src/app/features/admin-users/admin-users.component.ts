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
import { forkJoin } from 'rxjs';
import { dtoSchedule } from '../../models/dtos/schedule.dto';
import { DaySchedule } from '../../models/interfaces/daySchedule.model';
import { Doctor } from '../../models/interfaces/doctor.model';

import { SystemUser } from '../../models/interfaces/system-user.model';
import { AdminService } from '../../services/admin.service';
@Component({
  selector: 'app-admin-users',
  templateUrl: './admin-users.component.html',
  standalone: true,
  imports: [LucideAngularModule],
})
export class AdminUsersComponent implements OnInit {
  private adminService = inject(AdminService);
  private router = inject(Router);

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

  /*loadUsers(): void {
    this.loading.set(true);
    this.errorCarga.set('');

    forkJoin([
      this.adminService.getDoctors(),
      this.adminService.getSchedulers(),
      this.adminService.getBothRoleUsers(),
    ]).subscribe({
      next: ([doctors, schedulers, bothUsers]) => {
        forkJoin(
          doctors.map((d) => this.adminService.getSchedulesByDoctor(d.id)),
        ).subscribe({
          next: (allSchedules) => {
            const doctorUsers: SystemUser[] = doctors.map((d, i) => {
              const mapped = this.mapSchedulesToDoctor(allSchedules[i]);
              return {
                id: d.id,
                firstName: d.name, // ← era d.firstName
                lastName: '', // ← no existe en el modelo
                documentId: '', // ← no existe en el modelo
                roles: ['doctor'],
                doctorData: {
                  specialty: d.specialty,
                  startTime: (mapped.startTime ?? d.startTime) || '',
                  endTime: (mapped.endTime ?? d.endTime) || '',
                  interval: d.appointmentInterval,
                },
              };
            });
            this.systemUsers.set([...doctorUsers, ...schedulers, ...bothUsers]);
            this.loading.set(false);
          },
          error: () => {
            const doctorUsers: SystemUser[] = doctors.map((d) => ({
              id: d.id,
              firstName: d.name, // ← era d.firstName
              lastName: '', // ← no existe en el modelo
              documentId: '', // ← no existe en el modelo
              roles: ['doctor'],
              doctorData: {
                specialty: d.specialty,
                startTime: d.startTime || '',
                endTime: d.endTime || '',
                interval: d.appointmentInterval,
              },
            }));
            this.systemUsers.set([...doctorUsers, ...schedulers, ...bothUsers]);
            this.loading.set(false);
          },
        });
      },
      error: () => {
        this.errorCarga.set('Error al cargar los usuarios. Intente de nuevo.');
        this.loading.set(false);
      },
    });
  }
*/

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

  // ── Private ───────────────────────────────────────────────────────────────
  /*

  private mapSchedulesToDoctor(schedules: dtoSchedule[]): Partial<Doctor> {
    if (!schedules?.length) return {};
    const dayMap: Record<string, number> = {
      LUNES: 1,
      MARTES: 2,
      MIERCOLES: 3,
      JUEVES: 4,
      VIERNES: 5,
    };
    const daySchedules: { [day: number]: DaySchedule } = {};
    const workdays: number[] = [];
    schedules.forEach((s) => {
      const day = dayMap[s.workday];
      if (!day) return;
      daySchedules[day] = {
        startTime: s.startTime.substring(0, 5),
        endTime: s.endTime.substring(0, 5),
      };
      workdays.push(day);
    });
    return {
      startTime: schedules[0].startTime.substring(0, 5),
      endTime: schedules[0].endTime.substring(0, 5),
      daySchedules,
      workdays: workdays.sort((a, b) => a - b),
    };
  }
     */
}
