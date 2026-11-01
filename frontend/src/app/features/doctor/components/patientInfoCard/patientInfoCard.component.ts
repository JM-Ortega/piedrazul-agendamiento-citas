import {
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
  output,
  signal,
} from '@angular/core';
import { LucidePencil, LucideUser } from '@lucide/angular';
import { TooltipDirective } from '../../../../designSystem/atoms/tooltip/tooltip.directive';
import { calcAge } from '../../../../shared/helpers/patientValidation';
import { parseLocalDateString } from '../../../../shared/helpers/transformDateLocal';
import { Patient } from '../../../../shared/models/interfaces/patient.model';
import { FormatoPipe } from '../../../../shared/pipes/formatoPipe';
import { PatientEditPanelComponent } from '../patientEditPanel/patientEditPanel.component';

/**
 * Tarjeta con la información de un paciente: encabezado (avatar y nombre),
 * sección desplegable con sus datos en solo lectura y edición inline
 * mediante `app-patient-edit-panel`.
 *
 * Encapsula su propio estado de UI (desplegado / editando). El padre solo
 * entrega el `Patient` y escucha `saved` para actualizar su copia y mostrar
 * la notificación que corresponda.
 */
@Component({
  selector: 'app-patient-info-card',
  templateUrl: './patientInfoCard.component.html',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  imports: [
    LucideUser,
    LucidePencil,
    FormatoPipe,
    TooltipDirective,
    PatientEditPanelComponent,
  ],
})
export class PatientInfoCardComponent {
  // ── Inputs / Outputs ──────────────────────────────────────────────────────
  patient = input.required<Patient>();
  /** Emite el paciente ya actualizado por el backend tras guardar la edición. */
  saved = output<Patient>();

  // ── Estado de UI ──────────────────────────────────────────────────────────
  readonly mostrarInfo = signal(false);
  readonly isEditingPatient = signal(false);

  // ── Computed: datos derivados del paciente ───────────────────────────────
  readonly patientBirthDateFormatted = computed(() => {
    const p = this.patient();
    if (!p.birthDate) return 'No registra';
    return parseLocalDateString(p.birthDate).toLocaleDateString('es-CO', {
      day: '2-digit',
      month: '2-digit',
      year: 'numeric',
    });
  });

  readonly patientAge = computed(() => {
    const p = this.patient();
    if (!p.birthDate) return null;
    return calcAge(parseLocalDateString(p.birthDate));
  });

  // ── Acciones ──────────────────────────────────────────────────────────────
  toggleInfo(): void {
    this.mostrarInfo.update((v) => !v);
  }

  startEditPatient(): void {
    this.isEditingPatient.set(true);
  }

  /** Descarta la edición sin guardar y vuelve a la vista de solo lectura. */
  cancelEditPatient(): void {
    this.isEditingPatient.set(false);
  }

  /** Cierra la edición y notifica al padre con el paciente actualizado. */
  onPatientSaved(updated: Patient): void {
    this.isEditingPatient.set(false);
    this.saved.emit(updated);
  }
}
