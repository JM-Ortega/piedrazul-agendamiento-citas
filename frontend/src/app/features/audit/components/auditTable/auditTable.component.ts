import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { LucideSearch } from '@lucide/angular';
import { AuditEventResponse } from '../../models/audit.dto';
import { StatusBadgeComponent } from '../../../../designSystem/atoms/statusBadge/statusBadge.component';
import { ActionBadgeComponent } from '../../../../designSystem/atoms/actionBadge/actionBadge.component';
import { RoleBadgeComponent } from '../../../../designSystem/atoms/roleBadge/roleBadge.component';

/** Zona horaria con la que el backend interpreta los días de `from`/`to` */
const AUDIT_TIME_ZONE = 'America/Bogota';

/**
 * Tabla reutilizable para las 3 pages de auditoría (citas, usuarios, control médico).
 * `headClass`/`headTextClass` controlan el color del encabezado para diferenciar visualmente cada page.
 */
@Component({
  selector: 'app-audit-table',
  standalone: true,
  imports: [
    LucideSearch,
    StatusBadgeComponent,
    ActionBadgeComponent,
    RoleBadgeComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './auditTable.component.html',
})
export class AuditTableComponent {
  /** Registros a mostrar. Un arreglo vacío muestra el estado "sin resultados". */
  rows = input.required<AuditEventResponse[]>();
  /** Nombre legible de cada acción, indexado por su código. Si falta, se muestra el código. */
  actionNames = input<Record<string, string>>({});
  /** Clase Tailwind de fondo para la fila del encabezado */
  headClass = input('bg-blue-50');
  /** Clase Tailwind de color de texto para el encabezado. */
  headTextClass = input('text-blue-800');

  /**
   * Nombre a mostrar de quien ejecutó la acción.
   * @param event Registro de auditoría.
   * @returns Su nombre; "Sistema" si la acción la hizo el sistema; o un texto
   * genérico si la cuenta no tiene persona asociada.
   */
  protected actorLabel(event: AuditEventResponse): string {
    if (event.actorName) return event.actorName;
    return event.actorId === 'system'
      ? 'Sistema'
      : 'Cuenta sin persona asociada';
  }

  /**
   * Formatea un timestamp ISO a fecha legible en español, en hora de Colombia.
   * @param timestamp Instante en formato ISO-8601.
   * @returns Fecha formateada, ej. "18 de septiembre de 2026".
   */
  protected formatDate(timestamp: string): string {
    return new Date(timestamp).toLocaleDateString('es-CO', {
      day: 'numeric',
      month: 'long',
      year: 'numeric',
      timeZone: AUDIT_TIME_ZONE,
    });
  }

  /**
   * Formatea un timestamp ISO a hora legible en español (12 horas), en hora de Colombia.
   * @param timestamp Instante en formato ISO-8601.
   * @returns Hora formateada, ej. "12:42 p. m.".
   */
  protected formatTime(timestamp: string): string {
    return new Date(timestamp).toLocaleTimeString('es-CO', {
      hour: '2-digit',
      minute: '2-digit',
      hour12: true,
      timeZone: AUDIT_TIME_ZONE,
    });
  }
}
