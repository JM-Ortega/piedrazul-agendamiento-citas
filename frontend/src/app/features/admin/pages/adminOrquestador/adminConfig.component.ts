import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { LucidePencil, LucideSettings } from '@lucide/angular';
import { forkJoin, Observable } from 'rxjs';
import { PaginationComponent } from '../../../../design-system/molecules/pagination/pagination.component';
import {
  SortControlComponent,
  SortDirection,
  SortOption,
} from '../../../../design-system/molecules/sortControl/sortControl.component';
import {
  ToastComponent,
  ToastType,
} from '../../../../design-system/molecules/toast-message/toast.component';
import { PaginationMeta } from '../../../../shared/helpers/paginated-state';
import {
  DAY_TO_WORKDAY,
  workdayToNumber,
} from '../../../../shared/helpers/workday.util';
import { AppError } from '../../../../shared/models/interfaces/api-error.model';
import { DaySchedule } from '../../../../shared/models/interfaces/daySchedule.model';
import { Doctor } from '../../../../shared/models/interfaces/doctor.model';
import { DoctorCardComponent } from '../../components/doctorCard/doctorCard.component';
import {
  DoctorEditFormComponent,
  DoctorSaveEvent,
} from '../../components/doctorEditForm/doctorEditForm.component';
import { AdminModalsComponent } from '../../components/modals/modalHorarios/adminModals.component';
import { dtoSchedule } from '../../models/dtos/schedule.dto';
import { AdminService } from '../../service/admin.service';

@Component({
  selector: 'app-admin-config',
  templateUrl: './adminConfig.component.html',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    LucideSettings,
    LucidePencil,
    DoctorCardComponent,
    DoctorEditFormComponent,
    AdminModalsComponent,
    ToastComponent,
    PaginationComponent,
    SortControlComponent,
  ],
})
export class AdminConfigComponent implements OnInit {
  private adminService = inject(AdminService);

  // ── State ─────────────────────────────────────────────────────────────────
  doctors = signal<Doctor[]>([]);
  loading = signal(false);
  errorCarga = signal('');
  editingId = signal<string | null>(null);
  savedId = signal<string | null>(null);
  savingDoctorId = signal<string | null>(null);

  showConfirmModal = signal(false);
  doctorToToggle = signal<Doctor | null>(null);
  showForceModal = signal(false);
  forceModalMessage = signal('');
  showErrorModal = signal(false);
  errorGuardado = signal('');
  toastMessage = signal('');
  toastType = signal<ToastType | null>(null);
  // ── Paginacion ──────────────────────────────────────────────────────────────
  currentPage = signal(0);
  totalPages = signal(0);
  totalElements = signal(0);
  readonly PAGE_SIZE = 4;
  // ── Ordenamiento ──────────────────────────────────────────────────────────
  sortField = signal('appointmentInterval');
  sortDirection = signal<SortDirection>('asc');

  readonly sortOptions: SortOption[] = [
    { value: 'name', label: 'Nombre' },
    { value: 'status', label: 'Estado' },
    { value: 'appointmentInterval', label: 'Intervalo' },
  ];

  // ── Computed ──────────────────────────────────────────────────────────────
  totalSpecialties = computed(
    () => new Set(this.doctors().flatMap((d) => d.specialty)).size
  );
  avgInterval = computed(() => {
    const docs = this.doctors();
    return docs.length
      ? Math.round(
          docs.reduce((acc, d) => acc + d.appointmentInterval, 0) / docs.length
        )
      : 0;
  });

  pagination = computed<PaginationMeta | null>(() => {
    const total = this.totalPages();
    if (total <= 1) return null;
    const current = this.currentPage();
    return {
      pageNumber: current,
      pageSize: this.PAGE_SIZE,
      totalElements: this.totalElements(),
      totalPages: total,
      first: current === 0,
      last: current === total - 1,
    };
  });
  // ── Lifecycle ─────────────────────────────────────────────────────────────
  ngOnInit(): void {
    this.loadDoctors();
  }

  // ── Data loading ──────────────────────────────────────────────────────────
  loadDoctors(page = 0): void {
    this.loading.set(true);
    this.errorCarga.set('');
    const sort = `${this.sortField()},${this.sortDirection()}`;
    this.adminService.getDoctors(page, this.PAGE_SIZE, sort).subscribe({
      next: (response) => {
        this.currentPage.set(response.pageNumber);
        this.totalPages.set(response.totalPages);
        this.totalElements.set(response.totalElements);

        const doctors = response.content;
        if (!doctors.length) {
          this.doctors.set([]);
          this.loading.set(false);
          return;
        }

        forkJoin(
          doctors.map((d) => this.adminService.getSchedulesByDoctor(d.id))
        ).subscribe({
          next: (allSchedules) => {
            this.doctors.set(
              doctors.map((d, i) => ({
                ...d,
                workdays: d.workdays ?? [],
                ...this.mapSchedulesToDoctor(allSchedules[i]),
              }))
            );
            this.loading.set(false);
          },
          error: () => {
            this.doctors.set(
              doctors.map((d) => ({ ...d, workdays: d.workdays ?? [] }))
            );
            this.loading.set(false);
          },
        });
      },
      error: (err: AppError) => {
        this.errorCarga.set(err.message);
        this.loading.set(false);
      },
    });
  }

  // ── Edit handlers ─────────────────────────────────────────────────────────
  startEdit(doctor: Doctor): void {
    this.editingId.set(doctor.id);
    this.savedId.set(null);
  }

  cancelEdit(): void {
    this.editingId.set(null);
  }

  onFormSaved(event: DoctorSaveEvent): void {
    const { form, originalDoctor, removedWorkdays } = event;
    if (this.savingDoctorId() === form.id) return;
    const calls: Observable<unknown>[] = [];

    if (originalDoctor.appointmentInterval !== form.appointmentInterval)
      calls.push(
        this.adminService.updateAppointmentInterval(
          form.id,
          form.appointmentInterval
        )
      );

    if (
      originalDoctor.laborStart !== form.laborStart ||
      originalDoctor.laborEnd !== form.laborEnd
    ) {
      calls.push(
        this.adminService.updateLaborDate(
          form.id,
          form.laborStart,
          form.laborEnd
        )
      );
    }

    removedWorkdays.forEach((day) => {
      const workday = DAY_TO_WORKDAY[day];
      if (workday)
        calls.push(this.adminService.deleteSchedule(form.id, workday));
    });

    (form.workdays ?? []).forEach((day) => {
      const workday = DAY_TO_WORKDAY[day];
      if (!workday) return;

      const ds = form.daySchedules?.[day];
      const startTime = this.toTimeBackend(
        ds?.startTime ?? form.startTime ?? '05:00'
      );
      const endTime = this.toTimeBackend(
        ds?.endTime ?? form.endTime ?? '12:00'
      );

      calls.push(
        this.adminService.updateSchedule(form.id, workday, startTime, endTime)
      );
    });

    if (!calls.length) {
      this.cancelEdit();
      return;
    }

    forkJoin(calls).subscribe({
      next: () => {
        this.reloadDoctor(form.id, form);
        this.savedId.set(form.id);
        this.editingId.set(null);
        setTimeout(() => this.savedId.set(null), 3000);
        this.showToast('success', 'Configuración guardada correctamente.');
      },
      error: (err: AppError) => {
        this.errorGuardado.set(err.message);
        this.showErrorModal.set(true);
      },
    });
  }

  // ── Toggle handlers ───────────────────────────────────────────────────────
  openToggleModal(doctor: Doctor): void {
    this.doctorToToggle.set(doctor);
    this.showConfirmModal.set(true);
  }

  onCloseToggleModal(): void {
    this.showConfirmModal.set(false);
    this.doctorToToggle.set(null);
  }

  onConfirmToggle(): void {
    const doctor = this.doctorToToggle();
    if (!doctor) return;
    if (!doctor.status) {
      this.adminService
        .enableDoctor(doctor.id, doctor.laborStart, doctor.laborEnd)
        .subscribe({
          next: () => {
            this.doctors.update((list) =>
              list.map((d) => (d.id === doctor.id ? { ...d, status: true } : d))
            );
            this.onCloseToggleModal();
          },
          error: (err: AppError) => {
            this.forceModalMessage.set(err.message);
            this.onCloseToggleModal();
            this.showForceModal.set(true);
          },
        });
      return;
    }
    this.adminService.disableDoctor(doctor.id, false).subscribe({
      next: () => {
        this.doctors.update((list) =>
          list.map((d) => (d.id === doctor.id ? { ...d, status: false } : d))
        );
        this.onCloseToggleModal();
      },
      error: (err: AppError) => {
        this.forceModalMessage.set(err.message);
        this.showConfirmModal.set(false);
        this.showForceModal.set(true);
      },
    });
  }

  onConfirmForceDisable(): void {
    const target = this.doctorToToggle();
    if (!target) return;
    this.adminService.disableDoctor(target.id, true).subscribe({
      next: () => {
        this.doctors.update((list) =>
          list.map((d) => (d.id === target.id ? { ...d, status: false } : d))
        );
        this.showForceModal.set(false);
        this.forceModalMessage.set('');
        this.doctorToToggle.set(null);
      },
      error: (err: AppError) => {
        this.forceModalMessage.set(err.message);
      },
    });
  }

  onCancelForceDisable(): void {
    this.showForceModal.set(false);
    this.doctorToToggle.set(null);
    this.forceModalMessage.set('');
  }

  onCloseErrorModal(): void {
    this.showErrorModal.set(false);
    this.errorGuardado.set('');
  }

  // ── Helpers de template ───────────────────────────────────────────────────
  containerClass(doctor: Doctor): string {
    if (doctor.status === false) return 'border-gray-200 bg-gray-50';
    if (this.editingId() === doctor.id) return 'border-blue-400 bg-blue-50';
    return 'border-gray-100 bg-white hover:shadow-lg transition-shadow';
  }

  // ── Private ───────────────────────────────────────────────────────────────
  private toTimeBackend(time: string | undefined): string {
    if (!time) return '';
    return time.length === 5 ? `${time}:00` : time;
  }
  private showToast(type: ToastType, message: string, duration = 3000): void {
    this.toastType.set(type);
    this.toastMessage.set(message);
    setTimeout(() => {
      this.toastType.set(null);
      this.toastMessage.set('');
    }, duration);
  }

  private mapSchedulesToDoctor(schedules: dtoSchedule[]): Partial<Doctor> {
    if (!schedules?.length) return {};
    const daySchedules: Record<number, DaySchedule> = {};
    const workdays: number[] = [];
    schedules.forEach((s) => {
      const day = workdayToNumber(s.workday);
      if (day === null) return;
      daySchedules[day] = {
        startTime: s.startTime.substring(0, 5),
        endTime: s.endTime.substring(0, 5),
      };
      workdays.push(day);
    });
    workdays.sort((a, b) => a - b);
    return {
      startTime: schedules[0].startTime.substring(0, 5),
      endTime: schedules[0].endTime.substring(0, 5),
      daySchedules,
      workdays,
    };
  }

  private reloadDoctor(doctorId: string, fallback: Doctor): void {
    forkJoin([
      this.adminService.getDoctors(this.currentPage(), this.PAGE_SIZE),
      this.adminService.getSchedulesByDoctor(doctorId),
    ]).subscribe({
      next: ([doctorsPage, schedules]) => {
        const freshDoctor =
          doctorsPage.content.find((d) => d.id === doctorId) ?? fallback;
        const mapped = this.mapSchedulesToDoctor(schedules);
        this.doctors.update((list) =>
          list.map((d) =>
            d.id === doctorId ? { ...freshDoctor, ...mapped } : d
          )
        );
      },
      error: () => {
        this.doctors.update((list) =>
          list.map((d) => (d.id === doctorId ? fallback : d))
        );
      },
    });
  }
  // ── Pagination ───────────────────────────────────────────────────────────────
  goToPage(page: number): void {
    if (page < 0 || page >= this.totalPages() || page === this.currentPage())
      return;
    this.loadDoctors(page);
  }
  onSortFieldChange(field: string): void {
    this.sortField.set(field);
    this.loadDoctors(0);
  }

  onSortDirectionChange(direction: SortDirection): void {
    this.sortDirection.set(direction);
    this.loadDoctors(0);
  }
}
