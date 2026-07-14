import { Component, OnInit, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import {
  Activity,
  ArrowLeft,
  Bone,
  Building2,
  CircleAlert,
  CreditCard,
  Eye,
  EyeOff,
  Heart,
  Lock,
  LucideAngularModule,
  LucideIconData,
  Mail,
  User,
  UserPlus,
  Zap,
} from 'lucide-angular';
import {
  CreateUserDoctorFormComponent,
  DoctorFormData,
  SpecialtyOption,
} from '../../components/create-user-doctor-form/create-user-doctor-form.component';
import { CreateUserRolesComponent } from '../../components/create-user-roles/create-user-roles.component';
import { CreateUserConfirmModalComponent } from '../../components/modals/modals-create/create-user-confirm-modal.component';
import { CreateUserRequestDto } from '../../models/dtos/CreateUserRequestDto';
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
    LucideAngularModule,
    CreateUserRolesComponent,
    CreateUserDoctorFormComponent,
    CreateUserConfirmModalComponent,
  ],
  templateUrl: './admin-create-user.component.html',
})
export class AdminCreateUserComponent implements OnInit {
  // ── Icons ─────────────────────────────────────────────────────────────────
  readonly ArrowLeft = ArrowLeft;
  readonly UserPlus = UserPlus;
  readonly CreditCard = CreditCard;
  readonly Lock = Lock;
  readonly Eye = Eye;
  readonly EyeOff = EyeOff;
  readonly User = User;
  readonly Mail = Mail;
  readonly CircleAlert = CircleAlert;
  readonly Heart = Heart;
  readonly Bone = Bone;
  readonly Activity = Activity;
  readonly Zap = Zap;
  readonly Building2 = Building2;

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
    documentType: '',
    password: '',
    firstName: '',
    lastName: '',
    email: '',
    phone: '',
    specialty: [],
    laborStart: '',
    laborEnd: '',
    interval: 20,
    workDays: [1, 2, 3, 4, 5],
    startTime: '08:00',
    endTime: '12:00',
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

  // ── Getters ───────────────────────────────────────────────────────────────
  get hasDoctorRole() {
    return this.selectedRoles.includes('doctor');
  }
  get hasSchedulerRole() {
    return this.selectedRoles.includes('scheduler');
  }
  get shiftDurationMinutes() {
    return (
      this.timeToMinutes(this.userForm.endTime) -
      this.timeToMinutes(this.userForm.startTime)
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
      documentType: this.userForm.documentType,
      phone: this.userForm.phone,
      specialty: this.userForm.specialty,
      laborStart: this.userForm.laborStart,
      laborEnd: this.userForm.laborEnd,
      interval: this.userForm.interval,
      workDays: this.userForm.workDays,
      startTime: this.userForm.startTime,
      endTime: this.userForm.endTime,
    };
  }

  ngOnInit(): void {
    this.loadingSpecialties = true;
    this.adminService.getAllSpecialties().subscribe({
      next: (data) => {
        this.specialtyOptions = data.map((name) => ({
          name,
          ...this.getSpecialtyIcon(name),
        }));
        this.loadingSpecialties = false;
      },
      error: () => {
        this.loadingSpecialties = false;
      },
    });
    this.loadingDocumentTypes = true;
    this.adminService.getAllDocumentTypes().subscribe({
      next: (data) => {
        this.documentTypes = data;
        this.loadingDocumentTypes = false;
      },
      error: () => {
        this.loadingDocumentTypes = false;
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

  onDoctorFormChange(patch: Partial<DoctorFormData>): void {
    Object.assign(this.userForm, patch);
    if (this.submitted) {
      (Object.keys(patch) as (keyof FormErrors)[]).forEach((k) =>
        this.validateField(k)
      );
    }
  }

  // ── Submit ────────────────────────────────────────────────────────────────
  openConfirmModal(): void {
    this.submitted = true;
    this.submitError = null;
    if (!this.validateAll()) return;
    this.showConfirmModal = true;
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
        firstName: this.userForm.firstName.trim(),
        lastName: this.userForm.lastName.trim(),
        email: this.userForm.email.trim(),
        password: this.userForm.password,
      },
      doctor: this.hasDoctorRole
        ? {
            documentType: this.userForm.documentType,
            phone: this.userForm.phone,
            specialty: this.userForm.specialty,
            laborStart: this.userForm.laborStart,
            laborEnd: this.userForm.laborEnd,
            appointmentInterval: this.userForm.interval,
            schedules: this.userForm.workDays.map((day) => ({
              workday: DAY_VALUE_TO_WORKDAY[day],
              startTime: `${this.userForm.startTime}:00`,
              endTime: `${this.userForm.endTime}:00`,
            })),
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
    this.errors[field] = undefined;

    switch (field) {
      case 'documentId':
        if (!this.userForm.documentId.trim()) {
          this.errors.documentId = 'El documento de identidad es obligatorio.';
        } else if (!/^\d{5,15}$/.test(this.userForm.documentId)) {
          this.errors.documentId =
            'Debe contener entre 5 y 15 dígitos numéricos.';
        }
        break;

      case 'password':
        if (!this.userForm.password) {
          this.errors.password = 'La contraseña es obligatoria.';
        } else if (this.userForm.password.length < 6) {
          this.errors.password = 'Mínimo 6 caracteres.';
        }
        break;

      case 'firstName':
        if (!this.userForm.firstName.trim()) {
          this.errors.firstName = 'El nombre es obligatorio.';
        } else if (this.userForm.firstName.trim().length < 2) {
          this.errors.firstName = 'Mínimo 2 caracteres.';
        }
        break;

      case 'lastName':
        if (!this.userForm.lastName.trim()) {
          this.errors.lastName = 'El apellido es obligatorio.';
        } else if (this.userForm.lastName.trim().length < 2) {
          this.errors.lastName = 'Mínimo 2 caracteres.';
        }
        break;

      case 'roles':
        if (this.selectedRoles.length === 0) {
          this.errors.roles = 'Debe seleccionar al menos un rol.';
        }
        break;

      case 'email':
        if (!this.userForm.email.trim()) {
          this.errors.email = 'El correo electrónico es obligatorio.';
        } else if (
          !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(this.userForm.email.trim())
        ) {
          this.errors.email = 'Ingrese un correo electrónico válido.';
        }
        break;

      case 'documentType':
        if (this.hasDoctorRole && !this.userForm.documentType) {
          this.errors.documentType = 'El tipo de documento es obligatorio.';
        }
        break;

      case 'phone':
        if (this.hasDoctorRole) {
          if (!this.userForm.phone.trim()) {
            this.errors.phone = 'El teléfono es obligatorio.';
          } else if (!/^\d{10}$/.test(this.userForm.phone)) {
            // ← exactamente 10
            this.errors.phone =
              'El teléfono debe tener exactamente 10 dígitos.';
          }
        }
        break;

      case 'specialty':
        if (this.hasDoctorRole && this.userForm.specialty.length === 0) {
          this.errors.specialty = 'Debe seleccionar al menos una especialidad.';
        }
        break;

      case 'laborStart':
        if (this.hasDoctorRole) {
          if (!this.userForm.laborStart) {
            this.errors.laborStart =
              'La fecha de inicio laboral es obligatoria.';
          } else if (
            parseInt(this.userForm.laborStart.split('-')[0], 10) > 9999
          ) {
            this.errors.laborStart = 'El año no puede tener más de 4 dígitos.';
          } else if (
            this.userForm.laborEnd &&
            this.userForm.laborStart >= this.userForm.laborEnd
          ) {
            this.errors.laborStart =
              'La fecha de inicio debe ser anterior a la fecha de fin.';
          }
        }
        break;

      case 'laborEnd':
        if (this.hasDoctorRole) {
          if (!this.userForm.laborEnd) {
            this.errors.laborEnd = 'La fecha de fin laboral es obligatoria.';
          } else if (
            parseInt(this.userForm.laborEnd.split('-')[0], 10) > 9999
          ) {
            this.errors.laborEnd = 'El año no puede tener más de 4 dígitos.';
          } else if (
            this.userForm.laborStart &&
            this.userForm.laborStart >= this.userForm.laborEnd
          ) {
            this.errors.laborEnd =
              'La fecha de fin debe ser posterior a la fecha de inicio.';
          }
        }
        break;

      case 'startTime': {
        const start = this.timeToMinutes(this.userForm.startTime);
        const end = this.timeToMinutes(this.userForm.endTime);
        if (this.userForm.endTime && start >= end) {
          this.errors.startTime =
            'La hora de inicio no puede ser igual o posterior a la hora de fin.';
        }
        break;
      }

      case 'endTime': {
        const end = this.timeToMinutes(this.userForm.endTime);
        const start = this.timeToMinutes(this.userForm.startTime);
        if (this.userForm.startTime && start >= end) {
          this.errors.endTime =
            'La hora de fin debe ser posterior a la hora de inicio.';
        }
        break;
      }

      case 'interval': {
        const duration = this.shiftDurationMinutes;
        if (!this.userForm.interval || this.userForm.interval < 10) {
          this.errors.interval = 'El intervalo mínimo es 10 minutos.';
        } else if (this.userForm.interval > duration) {
          this.errors.interval = `El intervalo no puede superar la duración del turno (${duration} min).`;
        }
        break;
      }

      case 'workDays':
        if (this.hasDoctorRole && this.userForm.workDays.length === 0) {
          this.errors.workDays =
            'Debe seleccionar al menos un día de atención.';
        }
        break;
    }
  }

  private validateAll(): boolean {
    const fields: (keyof FormErrors)[] = [
      'documentId',
      'password',
      'firstName',
      'lastName',
      'email',
      'roles',
    ];
    if (this.hasDoctorRole) {
      fields.push(
        'documentType',
        'phone',
        'specialty',
        'laborStart',
        'laborEnd',
        'startTime',
        'endTime',
        'interval',
        'workDays'
      );
    }
    fields.forEach((f) => this.validateField(f));
    return Object.values(this.errors).every((e) => !e);
  }

  private timeToMinutes(t: string): number {
    if (!t) return 0;
    const [h, m] = t.split(':').map(Number);
    return h * 60 + m;
  }
  private getSpecialtyIcon(name: string): {
    icon: LucideIconData;
    colorClass: string;
  } {
    const map: Record<string, { icon: LucideIconData; colorClass: string }> = {
      MEDICINA_GENERAL: { icon: Heart, colorClass: 'text-red-700' },
      QUIROPRAXIA: { icon: Bone, colorClass: 'text-orange-700' },
      FISIOTERAPIA: { icon: Activity, colorClass: 'text-green-700' },
      TERAPIA_NEURAL: { icon: Zap, colorClass: 'text-purple-700' },
    };
    return map[name] ?? { icon: Building2, colorClass: 'text-gray-400' };
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
}
