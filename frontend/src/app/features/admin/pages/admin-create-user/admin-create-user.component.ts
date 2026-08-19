import {
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  Component,
  inject,
  OnInit,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import {
  LucideArrowLeft,
  LucideCircleAlert,
  LucideCreditCard,
  LucideDynamicIcon,
  LucideEye,
  LucideEyeOff,
  LucideMail,
  LucidePhone,
  LucideUser,
  LucideUserPlus,
} from '@lucide/angular';
import { ButtonComponent } from '../../../../design-system/atoms/button/button.component';
import { InputComponent } from '../../../../design-system/atoms/input/input.component';
import { SelectComponent } from '../../../../design-system/atoms/select/select.component';
import {
  getDocumentIdMaxLength,
  getDocumentIdSanitize,
  validateDocumentForType,
} from '../../../../shared/helpers/document-validation';
import { getSpecialtyMeta } from '../../../../shared/helpers/specialty-catalog';
import { timeToMinutes } from '../../../../shared/helpers/time-utils';
import { ToSelectOptionsPipe } from '../../../../shared/pipes/ToSelectOptionsPipe';
import {
  CreateUserDoctorFormComponent,
  SpecialtyOption,
} from '../../components/create-user-doctor-form/create-user-doctor-form.component';
import { CreateUserRolesComponent } from '../../components/create-user-roles/create-user-roles.component';
import { CreateUserConfirmModalComponent } from '../../components/modals/modal-create/create-user-confirm-modal.component';
import { CreateUserRequestDto } from '../../models/dtos/CreateUserRequestDto';
import { DoctorFormData } from '../../models/interfaces/DoctorFormData';
import { FormErrors } from '../../models/interfaces/FormErrors';
import { UserForm } from '../../models/interfaces/UserForm';
import { AdminService } from '../../service/admin.service';

type Role = 'doctor' | 'scheduler';

const DAY_VALUE_TO_WORKDAY: Record<number, string> = {
  1: 'LUNES',
  2: 'MARTES',
  3: 'MIERCOLES',
  4: 'JUEVES',
  5: 'VIERNES',
};

@Component({
  selector: 'app-admin-create-user',
  standalone: true,
  imports: [
    FormsModule,
    CreateUserRolesComponent,
    CreateUserDoctorFormComponent,
    CreateUserConfirmModalComponent,
    LucideDynamicIcon,
    LucideArrowLeft,
    LucideCircleAlert,
    LucideCreditCard,
    LucideMail,
    LucideUser,
    LucideUserPlus,
    LucidePhone,
    InputComponent,
    ButtonComponent,
    SelectComponent,
    ToSelectOptionsPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './admin-create-user.component.html',
})
export class AdminCreateUserComponent implements OnInit {
  // ── Dinamycs icons ─────────────────────────────────────────────────────────────────
  readonly Eye = LucideEye;
  readonly EyeOff = LucideEyeOff;

  // ── State ─────────────────────────────────────────────────────────────────
  showPassword = false;
  selectedRoles: Role[] = ['doctor'];
  errors: FormErrors = {};
  submitted = false;
  submitError: string | null = null;
  isSubmitting = false;
  showConfirmModal = false;

  specialtyOptions: SpecialtyOption[] = [];
  documentTypes: string[] = [];
  loadingSpecialties = false;
  loadingDocumentTypes = false;

  daysOfWeek = [
    { value: 1, label: 'Lunes' },
    { value: 2, label: 'Martes' },
    { value: 3, label: 'Miércoles' },
    { value: 4, label: 'Jueves' },
    { value: 5, label: 'Viernes' },
  ];

  userForm: UserForm = {
    documentId: '',
    identificationType: '',
    password: '',
    firstName: '',
    lastName: '',
    email: '',
    phone: '',
    specialty: [],
    laborStart: '',
    laborEnd: '',
    interval: null,
    workDays: [],
    startTime: '',
    endTime: '',
    bookingWindowWeeks: 0,
  };
  private readonly FIELD_ORDER: (keyof FormErrors)[] = [
    'roles',
    'documentId',
    'password',
    'firstName',
    'lastName',
    'email',
    'identificationType',
    'phone',
    'specialty',
    'laborStart',
    'laborEnd',
    'startTime',
    'endTime',
    'interval',
    'workDays',
  ];
  private readonly FIELD_TO_ELEMENT_ID: Partial<
    Record<keyof FormErrors, string>
  > = {
    documentId: 'documentId',
    password: 'password',
    firstName: 'firstName',
    lastName: 'lastName',
    email: 'email',
    identificationType: 'identificationType',
    phone: 'phone',
    laborStart: 'laborStart',
    laborEnd: 'laborEnd',
    startTime: 'startTime',
    endTime: 'endTime',
    interval: 'interval',
  };
  readonly timeOptions: string[] = (() => {
    const opts: string[] = [];
    for (let h = 7; h <= 12; h++)
      for (let m = 0; m < 60; m += 5) {
        if (h === 12 && m > 0) break;
        opts.push(
          `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`
        );
      }
    return opts;
  })();

  private router = inject(Router);
  private adminService = inject(AdminService);
  private cdr = inject(ChangeDetectorRef);

  // ── Getters ───────────────────────────────────────────────────────────────
  get hasDoctorRole() {
    return this.selectedRoles.includes('doctor');
  }
  get hasSchedulerRole() {
    return this.selectedRoles.includes('scheduler');
  }
  get shiftDurationMinutes() {
    return (
      timeToMinutes(this.userForm.endTime) -
      timeToMinutes(this.userForm.startTime)
    );
  }
  get maxInterval() {
    return Math.max(this.shiftDurationMinutes, 10);
  }
  get dayLabels(): Record<number, string> {
    return Object.fromEntries(this.daysOfWeek.map((d) => [d.value, d.label]));
  }
  get doctorFormData(): DoctorFormData {
    return {
      specialty: this.userForm.specialty,
      laborStart: this.userForm.laborStart,
      laborEnd: this.userForm.laborEnd,
      interval: this.userForm.interval,
      workDays: this.userForm.workDays,
      startTime: this.userForm.startTime,
      endTime: this.userForm.endTime,
      bookingWindowWeeks: this.userForm.bookingWindowWeeks,
    };
  }
  get isScheduleGroupTouched(): boolean {
    const f = this.userForm;
    return !!(
      f.laborStart ||
      f.laborEnd ||
      f.startTime ||
      f.endTime ||
      f.interval ||
      (f.workDays && f.workDays.length > 0) ||
      f.bookingWindowWeeks
    );
  }
  // ── OnInit ────────────────────────────────────────────────────────────────
  ngOnInit(): void {
    this.loadingSpecialties = true;
    this.adminService.getAllSpecialties().subscribe({
      next: (data) => {
        this.specialtyOptions = data.map((name) => {
          const meta = getSpecialtyMeta(name);
          return { name, icon: meta.icon, colorClass: meta.color };
        });
        this.loadingSpecialties = false;
        this.cdr.markForCheck();
      },
      error: () => {
        this.loadingSpecialties = false;
        this.cdr.markForCheck();
      },
    });
    this.loadingDocumentTypes = true;
    this.adminService.getAllDocumentTypes().subscribe({
      next: (data) => {
        this.documentTypes = data;
        this.loadingDocumentTypes = false;
        this.cdr.markForCheck();
      },
      error: () => {
        this.loadingDocumentTypes = false;
        this.cdr.markForCheck();
      },
    });
  }

  // ── Handlers de subcomponentes ────────────────────────────────────────────
  onRoleToggled(role: Role): void {
    if (this.selectedRoles.includes(role)) {
      if (this.selectedRoles.length > 1)
        this.selectedRoles = this.selectedRoles.filter((r) => r !== role);
    } else {
      this.selectedRoles = [...this.selectedRoles, role];
    }
    if (this.submitted) this.validateField('roles');
  }

  private readonly SCHEDULE_GROUP_FIELDS: (keyof FormErrors)[] = [
    'laborStart',
    'laborEnd',
    'startTime',
    'endTime',
    'interval',
    'workDays',
    'bookingWindowWeeks',
  ];

  onDoctorFormChange(patch: Partial<DoctorFormData>): void {
    Object.assign(this.userForm, patch);
    if (this.submitted) {
      const patchedKeys = Object.keys(patch) as (keyof FormErrors)[];
      const touchesGroup = patchedKeys.some((k) =>
        this.SCHEDULE_GROUP_FIELDS.includes(k)
      );
      const fieldsToValidate = touchesGroup
        ? Array.from(new Set([...patchedKeys, ...this.SCHEDULE_GROUP_FIELDS]))
        : patchedKeys;
      fieldsToValidate.forEach((k) => this.validateField(k));
    }
  }
  // ── Submit ────────────────────────────────────────────────────────────────
  openConfirmModal(): void {
    this.submitted = true;
    this.submitError = null;
    if (!this.validateAll()) {
      this.scrollToFirstError();
      return;
    }
    this.showConfirmModal = true;
  }
  private scrollToFirstError(): void {
    const firstErrorField = this.FIELD_ORDER.find((f) => this.errors[f]);
    if (!firstErrorField) return;

    // Casos sin input con id propio (roles, specialty, workDays) -> usa data-error-anchor
    const elementId =
      this.FIELD_TO_ELEMENT_ID[firstErrorField] ??
      `error-anchor-${firstErrorField}`;

    const el = document.getElementById(elementId);
    if (el) {
      el.scrollIntoView({ behavior: 'smooth', block: 'center' });
      // Si es un input/select real, además le damos foco
      if (el instanceof HTMLInputElement || el instanceof HTMLSelectElement) {
        el.focus({ preventScroll: true });
      }
    }
  }
  closeConfirmModal(): void {
    this.showConfirmModal = false;
  }

  confirmAndCreate(): void {
    this.showConfirmModal = false;
    this.isSubmitting = true;

    const roles = [
      ...(this.hasDoctorRole ? ['DOCTOR'] : []),
      ...(this.hasSchedulerRole ? ['SCHEDULER'] : []),
    ];

    const payload: CreateUserRequestDto = {
      user: {
        identification: this.userForm.documentId,
        identificationType: this.userForm.identificationType,
        phone: this.userForm.phone,
        firstName: this.userForm.firstName.trim(),
        lastName: this.userForm.lastName.trim(),
        email: this.userForm.email.trim(),
        password: this.userForm.password,
      },
      doctor: this.hasDoctorRole
        ? {
            specialty: this.userForm.specialty,
            laborStart: this.userForm.laborStart,
            laborEnd: this.userForm.laborEnd,
            appointmentInterval: this.userForm.interval ?? 0,
            schedules: this.userForm.workDays.map((day) => ({
              workday: DAY_VALUE_TO_WORKDAY[day],
              startTime: `${this.userForm.startTime}:00`,
              endTime: `${this.userForm.endTime}:00`,
            })),
            bookingWindowWeeks: this.userForm.bookingWindowWeeks,
          }
        : null,
      patient: null,
      roles,
    };
    this.adminService.createUser(payload).subscribe({
      next: () => {
        this.isSubmitting = false;
        this.router.navigate(['/admin/usuarios']);
      },
      error: (err) => {
        this.isSubmitting = false;
        this.submitError =
          err?.error?.message ?? 'Ocurrió un error al crear el usuario.';
      },
    });
  }

  navigateBack(): void {
    this.router.navigate(['/admin/usuarios']);
  }
  hasError(f: keyof FormErrors) {
    return !!this.errors[f];
  }

  // ── Validación (sin cambios) ───────────────────────────────────────────────
  validateField(field: keyof FormErrors): void {
    const message = this.computeFieldError(field);
    this.errors = { ...this.errors, [field]: message };
  }

  private computeFieldError(field: keyof FormErrors): string | undefined {
    switch (field) {
      case 'documentId': {
        const trimmed = this.userForm.documentId.trim();
        if (!trimmed) {
          return 'El documento de identidad es obligatorio.';
        }
        const formatError = validateDocumentForType(
          this.userForm.identificationType,
          trimmed
        );
        return formatError || undefined;
      }

      case 'password':
        if (!this.userForm.password) {
          return 'La contraseña es obligatoria.';
        }
        if (this.userForm.password.length < 6) {
          return 'Mínimo 6 caracteres.';
        }
        return undefined;

      case 'firstName':
        if (!this.userForm.firstName.trim()) {
          return 'El nombre es obligatorio.';
        }
        if (this.userForm.firstName.trim().length < 2) {
          return 'Mínimo 2 caracteres.';
        }
        return undefined;

      case 'lastName':
        if (!this.userForm.lastName.trim()) {
          return 'El apellido es obligatorio.';
        }
        if (this.userForm.lastName.trim().length < 2) {
          return 'Mínimo 2 caracteres.';
        }
        return undefined;

      case 'roles':
        return this.selectedRoles.length === 0
          ? 'Debe seleccionar al menos un rol.'
          : undefined;

      case 'email':
        if (!this.userForm.email.trim()) {
          return 'El correo electrónico es obligatorio.';
        }
        if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(this.userForm.email.trim())) {
          return 'Ingrese un correo electrónico válido.';
        }
        return undefined;

      case 'identificationType':
        return !this.userForm.identificationType
          ? 'El tipo de documento es obligatorio.'
          : undefined;

      case 'phone':
        if (!this.userForm.phone.trim()) {
          return 'El teléfono es obligatorio.';
        }
        if (!/^\d{10}$/.test(this.userForm.phone)) {
          return 'El teléfono debe tener exactamente 10 dígitos.';
        }
        return undefined;

      case 'specialty':
        return this.hasDoctorRole && this.userForm.specialty.length === 0
          ? 'Debe seleccionar al menos una especialidad.'
          : undefined;

      case 'laborStart':
        if (!this.hasDoctorRole || !this.isScheduleGroupTouched)
          return undefined;
        if (!this.userForm.laborStart) {
          return 'La fecha de inicio laboral es obligatoria.';
        }
        if (parseInt(this.userForm.laborStart.split('-')[0], 10) > 9999) {
          return 'El año no puede tener más de 4 dígitos.';
        }
        if (
          this.userForm.laborEnd &&
          this.userForm.laborStart >= this.userForm.laborEnd
        ) {
          return 'La fecha de inicio debe ser anterior a la fecha de fin.';
        }
        return undefined;

      case 'laborEnd':
        if (!this.hasDoctorRole || !this.isScheduleGroupTouched)
          return undefined;
        if (!this.userForm.laborEnd) {
          return 'La fecha de fin laboral es obligatoria.';
        }
        if (parseInt(this.userForm.laborEnd.split('-')[0], 10) > 9999) {
          return 'El año no puede tener más de 4 dígitos.';
        }
        if (
          this.userForm.laborStart &&
          this.userForm.laborStart >= this.userForm.laborEnd
        ) {
          return 'La fecha de fin debe ser posterior a la fecha de inicio.';
        }
        return undefined;

      case 'startTime': {
        if (!this.hasDoctorRole || !this.isScheduleGroupTouched)
          return undefined;
        if (!this.userForm.startTime) {
          return 'La hora de inicio es obligatoria.';
        }
        const start = timeToMinutes(this.userForm.startTime);
        const end = timeToMinutes(this.userForm.endTime);
        return this.userForm.endTime && start >= end
          ? 'La hora de inicio no puede ser igual o posterior a la hora de fin.'
          : undefined;
      }

      case 'endTime': {
        if (!this.hasDoctorRole || !this.isScheduleGroupTouched)
          return undefined;
        if (!this.userForm.endTime) {
          return 'La hora de fin es obligatoria.';
        }
        const end = timeToMinutes(this.userForm.endTime);
        const start = timeToMinutes(this.userForm.startTime);
        return this.userForm.startTime && start >= end
          ? 'La hora de fin debe ser posterior a la hora de inicio.'
          : undefined;
      }

      case 'interval': {
        if (!this.hasDoctorRole || !this.isScheduleGroupTouched)
          return undefined;
        if (!this.userForm.interval || this.userForm.interval < 10) {
          return 'El intervalo mínimo es 10 minutos.';
        }
        const duration = this.shiftDurationMinutes;
        if (duration > 0 && this.userForm.interval > duration) {
          return `El intervalo no puede superar la duración del turno (${duration} min).`;
        }
        return undefined;
      }

      case 'workDays':
        if (!this.hasDoctorRole || !this.isScheduleGroupTouched)
          return undefined;
        return this.userForm.workDays.length === 0
          ? 'Debe seleccionar al menos un día de atención.'
          : undefined;

      case 'bookingWindowWeeks':
        if (!this.hasDoctorRole || !this.isScheduleGroupTouched)
          return undefined;
        if (!this.userForm.bookingWindowWeeks) {
          return 'Debe indicar la ventana de reserva en semanas.';
        }
        if (
          this.userForm.bookingWindowWeeks < 1 ||
          this.userForm.bookingWindowWeeks > 10
        ) {
          return 'La ventana de reserva debe estar entre 1 y 10 semanas.';
        }
        return undefined;

      default:
        return undefined;
    }
  }
  private validateAll(): boolean {
    const fields: (keyof FormErrors)[] = [
      'documentId',
      'password',
      'identificationType',
      'phone',
      'firstName',
      'lastName',
      'email',
      'roles',
    ];
    if (this.hasDoctorRole) {
      fields.push(
        'specialty',
        'laborStart',
        'laborEnd',
        'startTime',
        'endTime',
        'interval',
        'workDays',
        'bookingWindowWeeks'
      );
    }
    fields.forEach((f) => this.validateField(f));
    return Object.values(this.errors).every((e) => !e);
  }

  inputClass(field: keyof FormErrors, extra = ''): string {
    return (
      'w-full border-2 rounded-xl py-3 text-base focus:outline-none focus:ring-2 ' +
      (this.hasError(field)
        ? 'border-red-400 focus:ring-red-400 bg-red-50 '
        : 'border-gray-300 focus:ring-blue-500 ') +
      extra
    );
  }
  getDocumentIdMaxLength(): number {
    return getDocumentIdMaxLength(this.userForm.identificationType);
  }

  getDocumentIdSanitize(): 'numeric' | 'alphanumeric' {
    return getDocumentIdSanitize(this.userForm.identificationType);
  }
}
