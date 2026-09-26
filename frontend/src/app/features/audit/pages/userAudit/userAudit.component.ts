import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  signal,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { map } from 'rxjs';
import { LucideShield, LucideUsers } from '@lucide/angular';
import { AuditFilters, EMPTY_AUDIT_FILTERS } from '../../models/audit.model';
import { toUserRow } from '../../models/audit.dto';
import { AuditTableComponent } from '../../components/auditTable/auditTable.component';
import { AuditFilterBarComponent } from '../../components/auditFilterBar/auditFilterBar.component';
import { AuditService } from '../../service/audit.service';

/**
 * Page de auditoría de usuarios: trae los logs de `AuditService`, los
 * normaliza a `AuditRow` con `toUserRow` y aplica los filtros de
 * `AuditFilterBarComponent` antes de pasarlos a `AuditTableComponent`.
 */
@Component({
  selector: 'app-user-audit',
  standalone: true,
  imports: [
    LucideShield,
    LucideUsers,
    AuditTableComponent,
    AuditFilterBarComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './userAudit.component.html',
})
export class UserAuditComponent {
  private auditService = inject(AuditService);

  /** Filtros activos de búsqueda/estado/fecha, controlados por la filter bar. */
  protected filters = signal<AuditFilters>(EMPTY_AUDIT_FILTERS);

  /** Logs de auditoría de usuarios, ya normalizados a `AuditRow`. */
  private userLogs = toSignal(
    this.auditService
      .getUserManagementLogs()
      .pipe(map((logs) => logs.map(toUserRow))),
    { initialValue: [] }
  );

  /** Filas a mostrar en la tabla: `userLogs` filtradas por `filters`. */
  protected rows = computed(() => {
    const f = this.filters();
    const q = f.search.toLowerCase();
    return this.userLogs().filter(
      (row) =>
        (!q || row.name.toLowerCase().includes(q)) &&
        (f.status === 'all' || row.status === f.status) &&
        (!f.date || row.timestamp.startsWith(f.date))
    );
  });
}
