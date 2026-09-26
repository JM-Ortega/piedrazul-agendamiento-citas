import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { LucideSearch } from '@lucide/angular';
import { AuditRow } from '../../models/audit.model';
import { StatusBadgeComponent } from '../../../../designSystem/atoms/statusBadge/statusBadge.component';
import { ActionBadgeComponent } from '../../../../designSystem/atoms/actionBadge/actionBadge.component';
import { RoleBadgeComponent } from '../../../../designSystem/atoms/roleBadge/roleBadge.component';

/**
 * Tabla reutilizable para las 3 pages de auditoría (citas, usuarios, control médico).
 * No conoce el origen de los datos: recibe `rows` ya normalizadas a `AuditRow`.
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
  rows = input.required<AuditRow[]>();
  headClass = input('bg-blue-50');
  headTextClass = input('text-blue-800');

  /**
   * Formatea un timestamp ISO a fecha legible en español (Colombia).
   * @param timestamp Fecha en formato ISO-8601.
   * @returns Fecha formateada, ej. "18 de septiembre de 2026".
   */
  protected formatDate(timestamp: string): string {
    return new Date(timestamp).toLocaleDateString('es-CO', {
      day: 'numeric',
      month: 'long',
      year: 'numeric',
    });
  }

  /**
   * Formatea un timestamp ISO a hora legible en español (Colombia), 12 horas.
   * @param timestamp Fecha en formato ISO-8601.
   * @returns Hora formateada, ej. "08:15 a.m.".
   */
  protected formatTime(timestamp: string): string {
    return new Date(timestamp).toLocaleTimeString('es-CO', {
      hour: '2-digit',
      minute: '2-digit',
      hour12: true,
    });
  }
}
