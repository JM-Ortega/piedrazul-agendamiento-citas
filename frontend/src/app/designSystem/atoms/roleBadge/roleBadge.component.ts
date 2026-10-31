import {
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
} from '@angular/core';

interface RoleCfg {
  label: string;
  cls: string;
}

const ROLE_CFG: Record<string, RoleCfg> = {
  ADMIN: {
    label: 'Administrador',
    cls: 'bg-purple-100 text-purple-700 border-purple-200',
  },
  SCHEDULER: {
    label: 'Agendador',
    cls: 'bg-teal-100 text-teal-700 border-teal-200',
  },
  DOCTOR: { label: 'Médico', cls: 'bg-blue-100 text-blue-700 border-blue-200' },
  PATIENT: { label: 'Paciente', cls: 'bg-sky-100 text-sky-700 border-sky-200' },
  AUDITOR: {
    label: 'Auditor',
    cls: 'bg-orange-100 text-orange-700 border-orange-200',
  },
};

/**
 * Badge de rol para las tablas de auditoría. Traduce el código de rol de Keycloak
 * a su nombre en español y le asigna color; si el rol no está en `ROLE_CFG`, muestra
 * el código tal cual con un estilo gris genérico.
 */
@Component({
  selector: 'app-role-badge',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './roleBadge.component.html',
})
export class RoleBadgeComponent {
  /** Código del rol (ej. 'DOCTOR', 'PATIENT'). */
  role = input.required<string>();
  protected cfg = computed<RoleCfg>(
    () =>
      ROLE_CFG[this.role()] ?? {
        label: this.role(),
        cls: 'bg-gray-100 text-gray-700 border-gray-200',
      }
  );
}
