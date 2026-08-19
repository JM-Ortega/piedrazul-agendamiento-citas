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
} from '../../../../shared/helpers/document-validation';
import { scrollToElementById } from '../../../../shared/helpers/scroll-to-element';
import { getSpecialtyMeta } from '../../../../shared/helpers/specialty-catalog';
import { timeToMinutes } from '../../../../shared/helpers/time-utils';
import { DAY_TO_WORKDAY } from '../../../../shared/helpers/workday.util';
import { AppError } from '../../../../shared/models/interfaces/api-error.model';
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
import { UserFormValidationService } from '../../service/user-form-validation.service';

type Role = 'doctor' | 'scheduler';

/**
 * Formulario de creación de usuario (doctor y/o scheduler). Arma el
 * payload combinando datos de cuenta y, si aplica, datos de doctor
 * (especialidad, horario), y delega la validación a UserFormValidationService.
 */
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
  // ── Iconos ────────────────────────────────────────────────────────────────
  readonly Eye = LucideEye;
  readonly EyeOff = LucideEyeOff;

  // ── Constantes ────────────────────────────────────────────────────────────
  daysOfWeek = [
    { value: 1, label: 'Lunes' },
    { value: 2, label: 'Martes' },
    { value: 3, label: 'Miércoles' },
    { value: 4, label: 'Jueves' },
    { value: 5, label: 'Viernes' },
  ];
  /** Opciones de hora de 07:00 a 12:00 en pasos de 5 minutos. */
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

  // ── Estado ────────────────────────────────────────────────────────────────
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
    bookingWindowWeeks: null,
  };

  // ── Inyecciones ───────────────────────────────────────────────────────────
  private router = inject(Router);
  private adminService = inject(AdminService);
  private validation = inject(UserFormValidationService);
  private cdr = inject(ChangeDetectorRef);

  // ── Getters ───────────────────────────────────────────────────────────────
  /** True si el rol 'doctor' está seleccionado. */
  get hasDoctorRole() {
    return this.selectedRoles.includes('doctor');
  }

  /** True si el rol 'scheduler' está seleccionado. */
  get hasSchedulerRole() {
    return this.selectedRoles.includes('scheduler');
  }

  /** Duración del turno en minutos, según startTime/endTime del formulario. */
  get shiftDurationMinutes() {
    return (
      timeToMinutes(this.userForm.endTime) -
      timeToMinutes(this.userForm.startTime)
    );
  }

  /** Intervalo máximo permitido entre citas (no menor a 10 minutos). */
  get maxInterval() {
    return Math.max(this.shiftDurationMinutes, 10);
  }

  /** Mapa de número de día a su etiqueta, derivado de daysOfWeek. */
  get dayLabels(): Record<number, string> {
    return Object.fromEntries(this.daysOfWeek.map((d) => [d.value, d.label]));
  }

  /** Subconjunto de userForm que consume el sub-formulario de doctor. */
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

  // ── Lifecycle ─────────────────────────────────────────────────────────────
  /** Carga especialidades y tipos de documento disponibles. */
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
  /** Agrega o quita un rol; revalida el campo 'roles' si ya se envió el form. */
  onRoleToggled(role: Role): void {
    if (this.selectedRoles.includes(role)) {
      if (this.selectedRoles.length > 1)
        this.selectedRoles = this.selectedRoles.filter((r) => r !== role);
    } else {
      this.selectedRoles = [...this.selectedRoles, role];
    }
    if (this.submitted) this.validateField('roles');
  }

  /**
   * Aplica el parche del sub-formulario de doctor a userForm. Si un campo
   * del grupo de horario cambió, revalida todo el grupo (son interdependientes).
   */
  onDoctorFormChange(patch: Partial<DoctorFormData>): void {
    Object.assign(this.userForm, patch);
    if (this.submitted) {
      const patchedKeys = Object.keys(patch) as (keyof FormErrors)[];
      const touchesGroup = patchedKeys.some((k) =>
        this.validation.SCHEDULE_GROUP_FIELDS.includes(k)
      );
      const fieldsToValidate = touchesGroup
        ? Array.from(
            new Set([...patchedKeys, ...this.validation.SCHEDULE_GROUP_FIELDS])
          )
        : patchedKeys;
      fieldsToValidate.forEach((k) => this.validateField(k));
    }
  }

  // ── Submit ────────────────────────────────────────────────────────────────
  /** Valida todo el formulario y abre el modal de confirmación si pasa. */
  openConfirmModal(): void {
    this.submitted = true;
    this.submitError = null;
    if (!this.validateAll()) {
      this.scrollToFirstError();
      return;
    }
    this.showConfirmModal = true;
  }

  /** Hace scroll hasta el primer campo con error, según el orden definido. */
  private scrollToFirstError(): void {
    const firstErrorField = this.validation.FIELD_ORDER.find(
      (f) => this.errors[f]
    );
    if (!firstErrorField) return;
    const elementId =
      this.validation.FIELD_TO_ELEMENT_ID[firstErrorField] ??
      `error-anchor-${firstErrorField}`;
    scrollToElementById(elementId);
  }

  closeConfirmModal(): void {
    this.showConfirmModal = false;
  }

  /** Arma el payload y crea el usuario; navega a la lista si tiene éxito. */
  confirmAndCreate(): void {
    this.showConfirmModal = false;
    this.isSubmitting = true;

    const roles = [
      ...(this.hasDoctorRole ? ['DOCTOR'] : []),
      ...(this.hasSchedulerRole ? ['SCHEDULER'] : []),
    ];

    const scheduleTouched = this.validation.isScheduleGroupTouched(
      this.userForm
    );

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
            laborStart: scheduleTouched ? this.userForm.laborStart : null,
            laborEnd: scheduleTouched ? this.userForm.laborEnd : null,
            appointmentInterval: scheduleTouched
              ? this.userForm.interval
              : null,
            schedules: scheduleTouched
              ? this.userForm.workDays.map((day) => ({
                  workday: DAY_TO_WORKDAY[day],
                  startTime: `${this.userForm.startTime}:00`,
                  endTime: `${this.userForm.endTime}:00`,
                }))
              : [],
            bookingWindowWeeks: scheduleTouched
              ? this.userForm.bookingWindowWeeks
              : null,
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
      error: (err: AppError) => {
        this.isSubmitting = false;
        this.submitError = err.message;
        this.cdr.markForCheck();
        scrollToElementById('submit-error-banner');
      },
    });
  }

  navigateBack(): void {
    this.router.navigate(['/admin/usuarios']);
  }

  // ── Validación ────────────────────────────────────────────────────────────
  hasError(f: keyof FormErrors) {
    return !!this.errors[f];
  }

  /** Valida un solo campo y actualiza `errors`. */
  validateField(field: keyof FormErrors): void {
    const message = this.validation.validateField(
      field,
      this.userForm,
      this.hasDoctorRole,
      this.selectedRoles.length
    );
    this.errors = { ...this.errors, [field]: message };
  }

  /** Valida todo el formulario; retorna true si no hay errores. */
  private validateAll(): boolean {
    this.errors = this.validation.validateAll(
      this.userForm,
      this.hasDoctorRole,
      this.selectedRoles.length
    );
    return Object.values(this.errors).every((e) => !e);
  }

  // ── Helpers de template ───────────────────────────────────────────────────
  /** Clases de un input, resaltando en rojo si el campo tiene error. */
  inputClass(field: keyof FormErrors, extra = ''): string {
    return (
      'w-full border-2 rounded-xl py-3 text-base focus:outline-none focus:ring-2 ' +
      (this.hasError(field)
        ? 'border-red-400 focus:ring-red-400 bg-red-50 '
        : 'border-gray-300 focus:ring-blue-500 ') +
      extra
    );
  }
  // ── Documento de identidad ────────────────────────────────────────────────
  getDocumentIdMaxLength(): number {
    return getDocumentIdMaxLength(this.userForm.identificationType);
  }

  getDocumentIdSanitize(): 'numeric' | 'alphanumeric' {
    return getDocumentIdSanitize(this.userForm.identificationType);
  }
}
