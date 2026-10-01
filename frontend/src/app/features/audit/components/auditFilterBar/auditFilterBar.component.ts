import {
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
  output,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { LucideChevronDown, LucideFilter, LucideSearch } from '@lucide/angular';
import { AuditFilterCatalog, AuditOutcome } from '../../models/audit.dto';
import { AuditFilters, EMPTY_AUDIT_FILTERS } from '../../models/audit.model';
import { ButtonComponent } from '../../../../designSystem/atoms/button/button.component';
import { InputComponent } from '../../../../designSystem/atoms/input/input.component';
import { DatepickerComponent } from '../../../../designSystem/molecules/datepicker/datepicker.component';
import {
  SelectComponent,
  SelectOption,
} from '../../../../designSystem/atoms/select/select.component';
import {
  parseLocalDateString,
  toIsoDateString,
} from '../../../../shared/helpers/transformDateLocal';

const MS_PER_DAY = 24 * 60 * 60 * 1000;

/** Id de uno de los 3 selects de la búsqueda avanzada, usado para los chips removibles. */
type ChipFieldId = 'outcome' | 'moduleCode' | 'action';

/**
 * Barra de filtros de auditoría, con un solo botón "Buscar" para todo.
 *
 * Arriba, siempre visibles: búsqueda por nombre/documento y el rango de
 * fechas Desde/Hasta (validados entre sí). Debajo, un panel colapsable de
 * "Búsqueda avanzada" (estilo disclosure, como `FiltersPanelComponent`),
 * la búsqueda exacta por id de objeto afectado y los selects de Resultado,
 * Módulo y Acción (Acción se filtra en vivo según el Módulo elegido).
 * El encabezado del panel muestra cuántos de esos 4 campos están activos en
 * la última búsqueda enviada, incluso estando cerrado; al abrirlo se ven los
 * campos y, debajo, un chip por cada select con valor en el borrador actual
 * (edición en curso, antes de presionar "Buscar"). Nada se envía hasta "Buscar",
 * que también cierra el panel.
 */
@Component({
  selector: 'app-audit-filter-bar',
  standalone: true,
  imports: [
    FormsModule,
    LucideSearch,
    LucideFilter,
    LucideChevronDown,
    ButtonComponent,
    InputComponent,
    DatepickerComponent,
    SelectComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './auditFilterBar.component.html',
})
export class AuditFilterBarComponent {
  /** Catálogo de filtros del backend. `null` mientras carga: los filtros que dependen de él quedan sin opciones ni límites. */
  catalog = input<AuditFilterCatalog | null>(null);

  /** Emite los filtros a aplicar cuando el usuario presiona "Buscar" o "Limpiar". */
  apply = output<AuditFilters>();

  /** Valores en edición; no se envían hasta "Buscar". */
  protected draft = signal<AuditFilters>(EMPTY_AUDIT_FILTERS);

  /** Última búsqueda efectivamente enviada. De aquí sale el badge del encabezado del panel avanzado. */
  protected appliedSnapshot = signal<AuditFilters>(EMPTY_AUDIT_FILTERS);

  /** True mientras el panel de búsqueda avanzada está desplegado. */
  protected advancedOpen = signal(false);

  /** `draft().from` como `Date`, para `app-datepicker`. `null` si está vacío. */
  protected fromDate = computed<Date | null>(() => {
    const from = this.draft().from;
    return from ? parseLocalDateString(from) : null;
  });

  /** `draft().to` como `Date`, para `app-datepicker`. `null` si está vacío. */
  protected toDate = computed<Date | null>(() => {
    const to = this.draft().to;
    return to ? parseLocalDateString(to) : null;
  });

  /** True si hay al menos un campo con valor en el borrador (para mostrar "Limpiar"). */
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

  protected outcomeOptions = computed<SelectOption[]>(() =>
    (this.catalog()?.outcomes ?? []).map((o) => ({
      value: o.code,
      label: o.name,
    }))
  );

  protected moduleOptions = computed<SelectOption[]>(() =>
    (this.catalog()?.modules ?? []).map((m) => ({
      value: m.code,
      label: m.name,
    }))
  );

  /**
   * Opciones de Acción disponibles. Si hay un módulo elegido en el borrador,
   * se limitan en vivo a las de ese módulo; si no, se muestran todas.
   */
  protected actionOptions = computed<SelectOption[]>(() => {
    const actions = this.catalog()?.actions ?? [];
    const moduleCode = this.draft().moduleCode;
    const scoped = moduleCode
      ? actions.filter((a) => a.moduleCode === moduleCode)
      : actions;
    return scoped.map((a) => ({ value: a.code, label: a.name }));
  });

  /** Mensaje de error del rango Desde–Hasta, o `null` si no aplica o es válido. */
  protected rangeError = computed<string | null>(() => {
    const { from, to } = this.draft();
    if (!from || !to) return null;
    const days = (Date.parse(to) - Date.parse(from)) / MS_PER_DAY;
    if (days < 0) return 'La fecha "Desde" no puede ser posterior a "Hasta"';
    const max = this.catalog()?.maxRangeDays;
    if (max && days > max) return `El rango no puede superar ${max} días`;
    return null;
  });

  /** Cantidad de los 4 campos de búsqueda avanzada con valor en la última búsqueda enviada. */
  protected advancedActiveCount = computed(() => {
    const a = this.appliedSnapshot();
    return [a.targetEntityId, a.outcome, a.moduleCode, a.action].filter(Boolean)
      .length;
  });
  protected hasAdvancedActiveFilters = computed(
    () => this.advancedActiveCount() > 0
  );

  /**
   * True si algún campo de búsqueda avanzada en el borrador difiere de lo
   * último aplicado (para el sufijo "(sin aplicar)" junto a los chips).
   */
  protected advancedIsDirty = computed(() => {
    const d = this.draft();
    const a = this.appliedSnapshot();
    return (
      d.targetEntityId !== a.targetEntityId ||
      d.outcome !== a.outcome ||
      d.moduleCode !== a.moduleCode ||
      d.action !== a.action
    );
  });

  /** Un chip por cada select (Resultado/Módulo/Acción) con valor en el borrador actual. */
  protected draftChips = computed<{ id: ChipFieldId; label: string }[]>(() => {
    const d = this.draft();
    const chips: { id: ChipFieldId; label: string }[] = [];
    if (d.outcome) {
      chips.push({ id: 'outcome', label: this.labelFor('outcome', d.outcome) });
    }
    if (d.moduleCode) {
      chips.push({
        id: 'moduleCode',
        label: this.labelFor('moduleCode', d.moduleCode),
      });
    }
    if (d.action) {
      chips.push({ id: 'action', label: this.labelFor('action', d.action) });
    }
    return chips;
  });

  /** Alterna la visibilidad del panel de búsqueda avanzada. */
  protected toggleAdvanced(): void {
    this.advancedOpen.update((open) => !open);
  }

  /**
   * Fusiona los campos recibidos sobre el borrador. Si cambia el módulo y la
   * acción elegida ya no pertenece a él, la acción se limpia.
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

  /**
   * `app-select` emite `string` plano; aquí se valida que el código elegido
   * sea realmente uno de los 3 resultados del catálogo antes de guardarlo en
   * `draft`, que lo tipa como `AuditOutcome | ''`.
   */
  protected patchOutcome(value: string): void {
    const isValid = this.catalog()?.outcomes.some((o) => o.code === value);
    this.patch({ outcome: isValid ? (value as AuditOutcome) : '' });
  }

  /** `app-input` emite `string | number | boolean | null`; aquí se reduce a `string` para `search`. */
  protected patchSearch(value: string | number | boolean | null): void {
    this.patch({ search: typeof value === 'string' ? value : '' });
  }

  /** `app-input` emite `string | number | boolean | null`; aquí se reduce a `string` para `targetEntityId`. */
  protected patchTargetEntityId(value: string | number | boolean | null): void {
    this.patch({ targetEntityId: typeof value === 'string' ? value : '' });
  }

  /** Traduce el `Date` emitido por `app-datepicker` a `from` (`YYYY-MM-DD`). */
  protected patchFrom(date: Date | null): void {
    this.patch({ from: date ? toIsoDateString(date) : '' });
  }

  /** Traduce el `Date` emitido por `app-datepicker` a `to` (`YYYY-MM-DD`). */
  protected patchTo(date: Date | null): void {
    this.patch({ to: date ? toIsoDateString(date) : '' });
  }

  /** Quita un filtro de búsqueda avanzada y vuelve a buscar de inmediato con el resto. */
  protected clearDraftField(id: ChipFieldId): void {
    this.patch({ [id]: '' });
    const next = this.draft();
    this.appliedSnapshot.set(next);
    this.apply.emit(next);
  }

  /**
   * Envía todo lo que haya en el borrador (búsqueda de arriba y avanzada),
   * cierra el panel avanzado y actualiza el badge del encabezado. No hace
   * nada si el rango de fechas es inválido.
   */
  protected submit(): void {
    if (this.rangeError()) return;
    const toApply: AuditFilters = {
      ...this.draft(),
      search: this.draft().search.trim(),
      targetEntityId: this.draft().targetEntityId.trim(),
    };
    this.draft.set(toApply);
    this.appliedSnapshot.set(toApply);
    this.advancedOpen.set(false);
    this.apply.emit(toApply);
  }

  /** Restablece todos los campos y el badge, y vuelve a buscar sin filtros. */
  protected clear(): void {
    this.draft.set(EMPTY_AUDIT_FILTERS);
    this.appliedSnapshot.set(EMPTY_AUDIT_FILTERS);
    this.advancedOpen.set(false);
    this.apply.emit(EMPTY_AUDIT_FILTERS);
  }

  /** Nombre legible de un código de catálogo para un campo de chip, buscando en la lista correspondiente. */
  private labelFor(field: ChipFieldId, code: string): string {
    const catalog = this.catalog();
    if (!catalog) return code;
    switch (field) {
      case 'outcome':
        return catalog.outcomes.find((o) => o.code === code)?.name ?? code;
      case 'moduleCode':
        return catalog.modules.find((m) => m.code === code)?.name ?? code;
      case 'action':
        return catalog.actions.find((a) => a.code === code)?.name ?? code;
    }
  }
}
