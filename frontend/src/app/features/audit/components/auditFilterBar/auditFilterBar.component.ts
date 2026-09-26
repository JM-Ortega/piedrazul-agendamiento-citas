import {
  ChangeDetectionStrategy,
  Component,
  computed,
  model,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AuditFilters, EMPTY_AUDIT_FILTERS } from '../../models/audit.model';

/**
 * Barra de filtros para las tablas de auditoría: búsqueda por nombre, y filtros por estado
 * (todos/exitoso/fallido) y fecha. Expone su estado como `model()` para que
 * el padre pueda usar binding bidireccional `[(filters)]`.
 */
@Component({
  selector: 'app-audit-filter-bar',
  standalone: true,
  imports: [FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './auditFilterBar.component.html',
})
export class AuditFilterBarComponent {
  /** Estado bidireccional: el padre puede usar [(filters)]. */
  filters = model<AuditFilters>(EMPTY_AUDIT_FILTERS);
  protected readonly EMPTY_AUDIT_FILTERS = EMPTY_AUDIT_FILTERS;

  /** True si hay al menos un filtro activo (para mostrar el botón "Limpiar"). */
  protected hasFilters = computed(() => {
    const f = this.filters();
    return !!f.search || f.status !== 'all' || !!f.date;
  });

  /**
   * Fusiona los campos recibidos sobre el valor actual de `filters` y
   * actualiza el signal.
   * @param partial Campos de `AuditFilters` a sobrescribir (los no incluidos conservan su valor actual).
   */
  protected patch(partial: Partial<AuditFilters>): void {
    this.filters.update((f) => ({ ...f, ...partial }));
  }
}
