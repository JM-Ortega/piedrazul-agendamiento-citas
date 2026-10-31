import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/**
 * Badge con el nombre legible de una acción auditada. Los códigos de acción los
 * define el backend.
 */
@Component({
  selector: 'app-action-badge',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './actionBadge.component.html',
})
export class ActionBadgeComponent {
  /** Texto a mostrar (nombre de la acción, ej. 'Cita agendada'). */
  label = input.required<string>();
}
