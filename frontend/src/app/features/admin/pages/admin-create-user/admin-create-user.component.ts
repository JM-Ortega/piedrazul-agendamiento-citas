import { CommonModule } from '@angular/common';
import { Component } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import {
  ArrowLeft,
  Building2,
  Calendar,
  CalendarRange,
  CircleAlert,
  CreditCard,
  Eye,
  EyeOff,
  Info,
  Lock,
  LucideAngularModule,
  Mail,
  Phone,
  Stethoscope,
  User,
  UserPlus,
} from 'lucide-angular';
import { AdminService } from '../../service/admin.service';

type Role = 'doctor' | 'scheduler';

interface UserForm {
  documentId: string;
  documentType: string;
  password: string;
  firstName: string;
  lastName: string;
  // Doctor-specific
  email: string;
  phone: string;
  specialty: string;
  laborStart: string; // ISO date: "YYYY-MM-DD"
  laborEnd: string; // ISO date: "YYYY-MM-DD"
  interval: number;
  workDays: number[];
  startTime: string;
  endTime: string;
}

interface FormErrors {
  documentId?: string;
  documentType?: string;
  password?: string;
  firstName?: string;
  lastName?: string;
  roles?: string;
  // Doctor-specific
  email?: string;
  phone?: string;
  specialty?: string;
  laborStart?: string;
  laborEnd?: string;
  startTime?: string;
  endTime?: string;
  interval?: string;
  workDays?: string;
}

// Map JS day index (0=Sun … 6=Sat) → backend workday string
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
  imports: [CommonModule, FormsModule, LucideAngularModule],
  templateUrl: './admin-create-user.component.html',
})
export class AdminCreateUserComponent {
  readonly ArrowLeft = ArrowLeft;
  readonly UserPlus = UserPlus;
  readonly Stethoscope = Stethoscope;
  readonly Calendar = Calendar;
  readonly CalendarRange = CalendarRange;
  readonly CreditCard = CreditCard;
  readonly Lock = Lock;
  readonly Eye = Eye;
  readonly EyeOff = EyeOff;
  readonly User = User;
  readonly Building2 = Building2;
  readonly CircleAlert = CircleAlert;
  readonly Info = Info;
  readonly Mail = Mail;
  readonly Phone = Phone;

  showPassword = false;
  selectedRoles: Role[] = ['doctor'];
  errors: FormErrors = {};
  submitted = false;

  /** Feedback visible al usuario tras submit */
  submitSuccess = false;
  submitError: string | null = null;
  isSubmitting = false;

  userForm: UserForm = {
    documentId: '',
    documentType: '',
    password: '',
    firstName: '',
    lastName: '',
    email: '',
    phone: '',
    specialty: '',
    laborStart: '',
    laborEnd: '',
    interval: 20,
    workDays: [1, 2, 3, 4, 5],
    startTime: '08:00',
    endTime: '14:00',
  };

  daysOfWeek = [
    { value: 1, label: 'Lunes' },
    { value: 2, label: 'Martes' },
    { value: 3, label: 'Miércoles' },
    { value: 4, label: 'Jueves' },
    { value: 5, label: 'Viernes' },
  ];
  documentTypes: { value: string; label: string }[] = [
    { value: 'CEDULA', label: 'Cédula de Ciudadanía' },
    { value: 'TARJETA_IDENTIDAD', label: 'Tarjeta de Identidad' },
    { value: 'REGISTRO_NACIMIENTO', label: 'Registro de Nacimiento' },
    { value: 'PASAPORTE', label: 'Pasaporte' },
  ];

  specialties: { value: string; label: string }[] = [
    { value: 'FISIOTERAPIA', label: 'Fisioterapia' },
    { value: 'TERAPIA_NEURAL', label: 'Terapia Neural' },
    { value: 'QUIROPRAXIA', label: 'Quiropraxia' },
  ];
  constructor(
    private router: Router,
    private adminService: AdminService,
  ) {}

  // ── Getters ──────────────────────────────────────────────────────────────

  get hasDoctorRole(): boolean {
    return this.selectedRoles.includes('doctor');
  }

  get hasSchedulerRole(): boolean {
    return this.selectedRoles.includes('scheduler');
  }

  get shiftDurationMinutes(): number {
    return (
      this.timeToMinutes(this.userForm.endTime) -
      this.timeToMinutes(this.userForm.startTime)
    );
  }

  get maxInterval(): number {
    return Math.max(this.shiftDurationMinutes, 10);
  }

  // ── Sanitización de inputs ────────────────────────────────────────────────

  onDocumentKeydown(event: KeyboardEvent): void {
    const allowedKeys = [
      'Backspace',
      'Delete',
      'ArrowLeft',
      'ArrowRight',
      'Tab',
      'Enter',
    ];
    if (allowedKeys.includes(event.key)) return;
    if (!/^\d$/.test(event.key)) event.preventDefault();
  }

  onDocumentInput(): void {
    this.userForm.documentId = this.userForm.documentId
      .replace(/\D/g, '')
      .slice(0, 15);
    if (this.submitted) this.validateField('documentId');
  }

  onNameKeydown(event: KeyboardEvent): void {
    const allowedKeys = [
      'Backspace',
      'Delete',
      'ArrowLeft',
      'ArrowRight',
      'Tab',
      'Enter',
      ' ',
    ];
    if (allowedKeys.includes(event.key)) return;
    if (!/^[a-zA-ZáéíóúÁÉÍÓÚñÑüÜ]$/.test(event.key)) event.preventDefault();
  }

  onNameInput(field: 'firstName' | 'lastName'): void {
    this.userForm[field] = this.userForm[field]
      .replace(/[^a-zA-ZáéíóúÁÉÍÓÚñÑüÜ\s]/g, '')
      .replace(/\s{2,}/g, ' ');
    if (this.submitted) this.validateField(field);
  }

  onIntervalKeydown(event: KeyboardEvent): void {
    const allowedKeys = [
      'Backspace',
      'Delete',
      'ArrowLeft',
      'ArrowRight',
      'ArrowUp',
      'ArrowDown',
      'Tab',
    ];
    if (allowedKeys.includes(event.key)) return;
    if (!/^\d$/.test(event.key)) event.preventDefault();
  }

  onIntervalInput(): void {
    const raw = String(this.userForm.interval).replace(/\D/g, '');
    this.userForm.interval = raw ? parseInt(raw, 10) : 10;
    if (this.submitted) this.validateField('interval');
  }

  onPhoneKeydown(event: KeyboardEvent): void {
    const allowedKeys = [
      'Backspace',
      'Delete',
      'ArrowLeft',
      'ArrowRight',
      'Tab',
      'Enter',
    ];
    if (allowedKeys.includes(event.key)) return;
    if (!/^\d$/.test(event.key)) event.preventDefault();
  }

  onPhoneInput(): void {
    this.userForm.phone = this.userForm.phone.replace(/\D/g, '').slice(0, 15);
    if (this.submitted) this.validateField('phone');
  }

  onEmailInput(): void {
    if (this.submitted) this.validateField('email');
  }

  // ── Lógica de tiempo ─────────────────────────────────────────────────────

  private timeToMinutes(time: string): number {
    if (!time) return 0;
    const [h, m] = time.split(':').map(Number);
    return h * 60 + m;
  }

  onStartTimeChange(): void {
    this.validateField('startTime');
    if (this.submitted) this.validateField('interval');
  }

  onEndTimeChange(): void {
    this.validateField('endTime');
    this.validateField('startTime');
    if (this.submitted) this.validateField('interval');
  }

  onLaborStartChange(): void {
    if (this.submitted) {
      this.validateField('laborStart');
      this.validateField('laborEnd');
    }
  }

  onLaborEndChange(): void {
    if (this.submitted) {
      this.validateField('laborEnd');
      this.validateField('laborStart');
    }
  }

  // ── Días de atención ─────────────────────────────────────────────────────

  toggleWorkDay(day: number): void {
    if (this.userForm.workDays.includes(day)) {
      this.userForm.workDays = this.userForm.workDays.filter((d) => d !== day);
    } else {
      this.userForm.workDays = [...this.userForm.workDays, day];
    }
    if (this.submitted) this.validateField('workDays');
  }

  isWorkDaySelected(day: number): boolean {
    return this.userForm.workDays.includes(day);
  }

  // ── Roles ─────────────────────────────────────────────────────────────────

  toggleRole(role: Role): void {
    this.selectedRoles = [role];
    if (this.submitted) this.validateField('roles');
  }
  getRoleLabel(role: Role): string {
    return role === 'doctor' ? 'Médico' : 'Agendador';
  }

  getRolesDisplayText(): string {
    return this.selectedRoles.map((r) => this.getRoleLabel(r)).join(' + ');
  }

  // ── Validación por campo ─────────────────────────────────────────────────

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
        } else if (this.userForm.password.length < 8) {
          this.errors.password = 'Mínimo 8 caracteres.';
        } else if (!/(?=.*[A-Z])/.test(this.userForm.password)) {
          this.errors.password = 'Debe incluir al menos una letra mayúscula.';
        } else if (!/(?=.*\d)/.test(this.userForm.password)) {
          this.errors.password = 'Debe incluir al menos un número.';
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

      // ── Doctor fields ────────────────────────────────────────────────────

      case 'email':
        if (this.hasDoctorRole) {
          if (!this.userForm.email.trim()) {
            this.errors.email = 'El correo electrónico es obligatorio.';
          } else if (
            !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(this.userForm.email.trim())
          ) {
            this.errors.email = 'Ingrese un correo electrónico válido.';
          }
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
          } else if (!/^\d{7,15}$/.test(this.userForm.phone)) {
            this.errors.phone = 'Debe contener entre 7 y 15 dígitos numéricos.';
          }
        }
        break;

      case 'specialty':
        if (this.hasDoctorRole && !this.userForm.specialty) {
          this.errors.specialty =
            'La especialidad es obligatoria para médicos.';
        }
        break;

      case 'laborStart':
        if (this.hasDoctorRole) {
          if (!this.userForm.laborStart) {
            this.errors.laborStart =
              'La fecha de inicio laboral es obligatoria.';
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
        if (end > this.timeToMinutes('14:00')) {
          this.errors.endTime =
            'El horario no puede extenderse más allá de las 14:00.';
        } else if (this.userForm.startTime && start >= end) {
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

  // ── Validación completa del formulario ───────────────────────────────────

  private validateAll(): boolean {
    const fields: (keyof FormErrors)[] = [
      'documentId',
      'documentType',
      'password',
      'firstName',
      'lastName',
      'roles',
    ];

    if (this.hasDoctorRole) {
      fields.push(
        'email',
        'phone',
        'specialty',
        'laborStart',
        'laborEnd',
        'startTime',
        'endTime',
        'interval',
        'workDays',
      );
    }

    fields.forEach((f) => this.validateField(f));

    return Object.values(this.errors).every((e) => !e);
  }

  // ── Submit ────────────────────────────────────────────────────────────────

  handleCreateUser(): void {
    this.submitted = true;
    this.submitSuccess = false;
    this.submitError = null;

    if (!this.validateAll()) return;

    this.isSubmitting = true;

    // ── Caso: solo Agendador ──────────────────────────────────────────────
    if (this.hasSchedulerRole && !this.hasDoctorRole) {
      this.adminService
        .createScheduler({
          documentId: this.userForm.documentId,
          password: this.userForm.password,
          firstName: this.userForm.firstName.trim(),
          lastName: this.userForm.lastName.trim(),
        })
        .subscribe({
          next: () => {
            this.isSubmitting = false;
            this.router.navigate(['/admin/usuarios']);
          },
          error: (err) => {
            this.isSubmitting = false;
            this.submitError =
              err?.error?.message ??
              'Ocurrió un error al crear el agendador. Inténtalo de nuevo.';
            console.error('Error al crear agendador:', err);
          },
        });
      return;
    }

    // ── Caso: Doctor (con o sin rol Agendador simultáneo) ─────────────────
    if (this.hasDoctorRole) {
      const schedules = this.userForm.workDays.map((day) => ({
        workday: DAY_VALUE_TO_WORKDAY[day],
        startTime: this.userForm.startTime,
        endTime: this.userForm.endTime,
      }));

      this.adminService
        .createDoctor({
          firstName: this.userForm.firstName.trim(),
          lastName: this.userForm.lastName.trim(),
          identification: this.userForm.documentId,
          documentType: this.userForm.documentType,
          phone: this.userForm.phone,
          specialty: [this.userForm.specialty],
          laborStart: this.userForm.laborStart,
          laborEnd: this.userForm.laborEnd,
          appointmentInterval: this.userForm.interval,
          schedules,
          email: this.userForm.email.trim(),
          password: this.userForm.password,
        })
        .subscribe({
          next: () => {
            this.isSubmitting = false;
            this.router.navigate(['/admin/usuarios']);
          },
          error: (err) => {
            this.isSubmitting = false;
            this.submitError =
              err?.error?.message ??
              'Ocurrió un error al crear el médico. Inténtalo de nuevo.';
            console.error('Error al crear médico:', err);
          },
        });
    }
  }

  navigateBack(): void {
    this.router.navigate(['/admin/usuarios']);
  }

  // ── Helpers para el template ──────────────────────────────────────────────

  hasError(field: keyof FormErrors): boolean {
    return !!this.errors[field];
  }

  inputClass(field: keyof FormErrors, extra = ''): string {
    const base =
      'w-full border-2 rounded-xl py-3 text-base focus:outline-none focus:ring-2 ';
    const state = this.hasError(field)
      ? 'border-red-400 focus:ring-red-400 bg-red-50 '
      : 'border-gray-300 focus:ring-blue-500 ';
    return base + state + extra;
  }
}
