import {
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
  output,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AuditActionOption, AuditFilterCatalog } from '../../models/audit.dto';
import { AuditFilters, EMPTY_AUDIT_FILTERS } from '../../models/audit.model';

const MS_PER_DAY = 24 * 60 * 60 * 1000;

/**
 * Barra de filtros de auditoría. Siempre visibles: búsqueda por nombre/documento
 * (prefijo) y por id exacto del objeto afectado. Un desplegable de "Búsqueda
 * avanzada" agrega rango de fechas, resultado, acción y módulo, con las
 * opciones y límites tomados del catálogo del backend. Los valores se editan
 * localmente y solo se emiten al presionar "Buscar" o "Limpiar".
 */
@Component({
  selector: 'app-audit-filter-bar',
  standalone: true,
  imports: [FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './auditFilterBar.component.html',
})
export class AuditFilterBarComponent {
  /** Catálogo de filtros del backend. `null` mientras carga: los filtros que dependen de él quedan sin opciones ni límites. */
  catalog = input<AuditFilterCatalog | null>(null);

  /** Emite los filtros a aplicar cuando el usuario presiona "Buscar" o "Limpiar". */
  apply = output<AuditFilters>();

  /** Valores en edición; no se aplican hasta que el usuario lo confirme. */
  protected draft = signal<AuditFilters>(EMPTY_AUDIT_FILTERS);

  /** True mientras el panel de búsqueda avanzada está desplegado. */
  protected advancedOpen = signal(false);

  /** True si hay al menos un filtro con valor (para mostrar el botón "Limpiar"). */
  protected hasFilters = computed(() => {
    const d = this.draft();
    return (
      !!d.search ||
      !!d.targetEntityId ||
      !!d.from ||
      !!d.to ||
      !!d.outcome ||
      !!d.action ||
      !!d.moduleCode
    );
  });

  /**
   * Acciones del catálogo disponibles para elegir. Si hay un módulo
   * seleccionado, se limitan a las de ese módulo.
   */
  protected actionOptions = computed<AuditActionOption[]>(() => {
    const actions = this.catalog()?.actions ?? [];
    const moduleCode = this.draft().moduleCode;
    return moduleCode
      ? actions.filter((a) => a.moduleCode === moduleCode)
      : actions;
  });

  /**
   * Mensaje de error del rango de fechas, o `null` si es válido.
   */
  protected rangeError = computed<string | null>(() => {
    const { from, to } = this.draft();
    if (!from || !to) return null;
    const days = (Date.parse(to) - Date.parse(from)) / MS_PER_DAY;
    if (days < 0) return 'La fecha "Desde" no puede ser posterior a "Hasta"';
    const max = this.catalog()?.maxRangeDays;
    if (max && days > max) return `El rango no puede superar ${max} días`;
    return null;
  });

  /** Alterna la visibilidad del panel de búsqueda avanzada. */
  protected toggleAdvanced(): void {
    this.advancedOpen.update((open) => !open);
  }

  /**
   * Fusiona los campos recibidos sobre los valores en edición. Si cambia el
   * módulo y la acción elegida ya no pertenece a él, la acción se limpia.
   * @param partial Campos de `AuditFilters` a sobrescribir (los no incluidos conservan su valor).
   */
  protected patch(partial: Partial<AuditFilters>): void {
    this.draft.update((d) => {
      const next = { ...d, ...partial };
      if (
        partial.moduleCode !== undefined &&
        next.action &&
        !this.catalog()?.actions.some(
          (a) => a.code === next.action && a.moduleCode === next.moduleCode
        )
      ) {
        next.action = '';
      }
      return next;
    });
  }

  /** Emite los filtros en edición, salvo que el rango de fechas sea inválido. */
  protected submit(): void {
    if (this.rangeError()) return;
    this.apply.emit({
      ...this.draft(),
      search: this.draft().search.trim(),
      targetEntityId: this.draft().targetEntityId.trim(),
    });
  }

  /** Restablece los campos y emite los filtros vacíos. */
  protected clear(): void {
    this.draft.set(EMPTY_AUDIT_FILTERS);
    this.apply.emit(EMPTY_AUDIT_FILTERS);
  }
}
