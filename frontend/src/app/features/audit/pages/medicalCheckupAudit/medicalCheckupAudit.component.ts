import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  signal,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { map } from 'rxjs';
import { LucideShield, LucideFileText } from '@lucide/angular';
import { AuditFilters, EMPTY_AUDIT_FILTERS } from '../../models/audit.model';
import { toRecordRow } from '../../models/audit.dto';
import { AuditTableComponent } from '../../components/auditTable/auditTable.component';
import { AuditFilterBarComponent } from '../../components/auditFilterBar/auditFilterBar.component';
import { AuditService } from '../../service/audit.service';

/**
 * Page de auditoría de control médico: trae los logs de `AuditService`, los
 * normaliza a `AuditRow` con `toRecordRow` y aplica los filtros de
 * `AuditFilterBarComponent` antes de pasarlos a `AuditTableComponent`.
 */
@Component({
  selector: 'app-medical-checkup-audit',
  standalone: true,
  imports: [
    LucideShield,
    LucideFileText,
    AuditTableComponent,
    AuditFilterBarComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './medicalCheckupAudit.component.html',
})
export class MedicalCheckupAuditComponent {
  private auditService = inject(AuditService);

  /** Filtros activos de búsqueda/estado/fecha, controlados por la filter bar. */
  protected filters = signal<AuditFilters>(EMPTY_AUDIT_FILTERS);

  /** Logs de auditoría de control médico, ya normalizados a `AuditRow`. */
  private recordLogs = toSignal(
    this.auditService
      .getMedicalRecordLogs()
      .pipe(map((logs) => logs.map(toRecordRow))),
    { initialValue: [] }
  );

  /** Filas a mostrar en la tabla: `recordLogs` filtradas por `filters`. */
  protected rows = computed(() => {
    const f = this.filters();
    const q = f.search.toLowerCase();
    return this.recordLogs().filter(
      (row) =>
        (!q || row.name.toLowerCase().includes(q)) &&
        (f.status === 'all' || row.status === f.status) &&
        (!f.date || row.timestamp.startsWith(f.date))
    );
  });
}
