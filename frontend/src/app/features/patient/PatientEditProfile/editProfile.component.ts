import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import {
  LucideCalendar,
  LucideCircleCheck,
  LucideCreditCard,
  LucideDynamicIcon,
  LucideMail,
  LucidePencil,
  LucidePhone,
  LucideSave,
  LucideShieldAlert,
  LucideUser,
  LucideUsers,
  LucideX,
} from '@lucide/angular';
import { timer } from 'rxjs';
import {
  PatientService,
  UpdatePatientRequest,
} from '../../../core/services/register.service';
import { ButtonComponent } from '../../../designSystem/atoms/button/button.component';
import { parseLocalDateString } from '../../../shared/helpers/transformDateLocal';
import { Patient } from '../../../shared/models/interfaces/patient.model';
import { FormatoPipe } from '../../../shared/pipes/formatoPipe';
import {
  EMPTY_PATIENT_FORM,
  PatientFormComponent,
  PatientFormData,
} from '../../../shared/components/forms/patientForm/patientForm.component';

/**
 * Vista del perfil del paciente autenticado. Alterna entre modo consulta
 * (datos en solo lectura) y modo edición (reutiliza `PatientFormComponent`
 * con el documento bloqueado).
 */
@Component({
  selector: 'app-edit-profile',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    FormsModule,
    ButtonComponent,
    PatientFormComponent,
    LucideDynamicIcon,
    LucideUser,
    LucidePencil,
    LucideSave,
    LucideX,
    LucideCircleCheck,
  ],
  templateUrl: './editProfile.component.html',
})
export class EditProfileComponent {
  private patientService = inject(PatientService);
  private destroyRef = inject(DestroyRef);
  private formatoPipe = new FormatoPipe();
  private form = viewChild(PatientFormComponent);

  /** Paciente autenticado, tomado de la caché `me` del servicio. */
  readonly patient = this.patientService.me;
  /** `true` mientras se muestra el formulario en lugar de los datos de solo lectura. */
  readonly editing = signal(false);
  /** `true` mientras se envía la actualización al backend. */
  readonly saving = signal(false);
  /** `true` durante unos segundos tras guardar, para mostrar el banner de éxito. */
  readonly saved = signal(false);
  /** Valor actual del formulario, sincronizado en cada cambio. */
  readonly formValue = signal<PatientFormData>({ ...EMPTY_PATIENT_FORM });
  /** Campos que se muestran en el formulario pero no se pueden modificar. */
  readonly lockedFields = ['identificationType', 'identification'] as const;

  /** Datos del paciente para la vista de solo lectura. */
  readonly fields = computed(() => {
    const p = this.patient();
    if (!p) return [];
    const birth = p.birthDate ? parseLocalDateString(p.birthDate) : null;
    return [
      { label: 'Nombres', icon: LucideUser, value: p.firstName },
      { label: 'Apellidos', icon: LucideUser, value: p.lastName },
      {
        label: 'Tipo de documento',
        icon: LucideCreditCard,
        value: this.formatoPipe.transform(p.identificationType),
      },
      {
        label: 'Número de documento',
        icon: LucideCreditCard,
        value: p.identification,
      },
      {
        label: 'Género',
        icon: LucideUsers,
        value: this.formatoPipe.transform(p.sex),
      },
      {
        label: 'Fecha de nacimiento',
        icon: LucideCalendar,
        value: birth
          ? birth.toLocaleDateString('es-CO', {
              day: 'numeric',
              month: 'long',
              year: 'numeric',
            })
          : '',
      },
      { label: 'Correo electrónico', icon: LucideMail, value: p.email ?? '' },
      { label: 'Teléfono', icon: LucidePhone, value: p.phone },
      {
        label: 'Teléfono de acudiente',
        icon: LucideShieldAlert,
        value: p.guardianPhone ?? '',
      },
    ];
  });

  /**
   * `true` si el formulario difiere del perfil guardado. Se recalcula en cada
   * cambio, así que vuelve a `false` si el usuario restaura los valores originales.
   * Habilita el botón Guardar.
   */
  readonly hasChanges = computed(() => {
    const p = this.patient();
    if (!p || !this.editing()) return false;

    const original = this.toFormData(p);
    const current = this.formValue();
    return (Object.keys(original) as (keyof PatientFormData)[]).some(
      (key) => original[key] !== current[key]
    );
  });

  constructor() {
    this.patientService
      .getMe()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe();
  }

  /** Precarga el formulario con los datos actuales del paciente y activa el modo edición. */
  startEditing(): void {
    const p = this.patient();
    if (!p) return;
    this.formValue.set(this.toFormData(p));
    this.editing.set(true);
  }

  /** Al cerrar el modo edición el formulario se destruye, así que sus errores se reinician solos. */
  cancelEditing(): void {
    this.editing.set(false);
  }

  /**
   * Valida el formulario y, si es válido, envía solo los campos editables al
   * backend (sin tipo ni número de documento). Los opcionales vacíos se envían
   * como `undefined` para que el backend los reciba como `null`. Al terminar
   * vuelve al modo consulta y muestra el banner de éxito durante 4 segundos.
   */
  save(): void {
    if (!this.form()?.validate()) return;

    const v = this.formValue();
    const request: UpdatePatientRequest = {
      firstName: v.firstName,
      lastName: v.lastName,
      phone: v.phone,
      sex: v.sex,
      birthDate: v.birthDate,
      email: v.email || undefined,
      guardianPhone: v.guardianPhone || undefined,
    };

    this.saving.set(true);
    this.patientService
      .updateMe(request)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.saving.set(false);
          this.editing.set(false);
          this.saved.set(true);
          timer(4000)
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe(() => this.saved.set(false));
        },
        error: () => this.saving.set(false),
      });
  }

  /** Convierte un `Patient` al formato del formulario, normalizando los opcionales a `''`. */
  private toFormData(p: Patient): PatientFormData {
    return {
      identificationType: p.identificationType,
      identification: p.identification,
      firstName: p.firstName,
      lastName: p.lastName,
      phone: p.phone,
      sex: p.sex,
      birthDate: p.birthDate,
      email: p.email ?? '',
      guardianPhone: p.guardianPhone ?? '',
    };
  }
}
