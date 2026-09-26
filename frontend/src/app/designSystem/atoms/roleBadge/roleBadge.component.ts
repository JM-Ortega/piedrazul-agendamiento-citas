import {
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
} from '@angular/core';

const ROLE_CLS: Record<string, string> = {
  paciente: 'bg-sky-100 text-sky-700 border-sky-200',
  doctor: 'bg-blue-100 text-blue-700 border-blue-200',
  agendador: 'bg-teal-100 text-teal-700 border-teal-200',
  auditor: 'bg-orange-100 text-orange-700 border-orange-200',
  administrador: 'bg-purple-100 text-purple-700 border-purple-200',
};

/**
 * Badge de rol para las tablas de auditoría. Resuelve el color a partir de
 * `ROLE_CLS` (comparando en minúsculas); si el rol no está en el mapa, cae a
 * un estilo gris genérico.
 */
@Component({
  selector: 'app-role-badge',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './roleBadge.component.html',
})
export class RoleBadgeComponent {
  /** Rol de quien realizó la acción (ej. 'doctor', 'paciente', 'auditor'). */
  role = input.required<string>();
  protected cls = computed(
    () =>
      ROLE_CLS[this.role().toLowerCase()] ??
      'bg-gray-100 text-gray-700 border-gray-200'
  );
}
