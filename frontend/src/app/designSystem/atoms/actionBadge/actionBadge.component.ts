import {
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
} from '@angular/core';

interface ActionCfg {
  label: string;
  cls: string;
}

const ACTION_CFG: Record<string, ActionCfg> = {
  scheduled: {
    label: 'Agendar',
    cls: 'bg-blue-100 text-blue-700 border-blue-200',
  },
  cancelled: {
    label: 'Cancelar',
    cls: 'bg-red-100 text-red-700 border-red-200',
  },
  created: {
    label: 'Crear',
    cls: 'bg-green-100 text-green-700 border-green-200',
  },
  modified: {
    label: 'Modificar',
    cls: 'bg-blue-100 text-blue-700 border-blue-200',
  },
  deactivated: {
    label: 'Deshabilitar',
    cls: 'bg-red-100 text-red-700 border-red-200',
  },
  activated: {
    label: 'Activado',
    cls: 'bg-green-100 text-green-700 border-green-200',
  },
};

/**
 * Badge de acción para las tablas de auditoría. Resuelve el color y la
 * etiqueta a partir de `ACTION_CFG`; si `action` no está en el mapa, cae a
 * un estilo gris genérico usando el valor crudo como etiqueta.
 */
@Component({
  selector: 'app-action-badge',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './actionBadge.component.html',
})
export class ActionBadgeComponent {
  /** Acción de auditoría (ej. 'scheduled', 'created', 'deactivated'). */
  action = input.required<string>();
  protected cfg = computed<ActionCfg>(
    () =>
      ACTION_CFG[this.action()] ?? {
        label: this.action(),
        cls: 'bg-gray-100 text-gray-700 border-gray-200',
      }
  );
}
