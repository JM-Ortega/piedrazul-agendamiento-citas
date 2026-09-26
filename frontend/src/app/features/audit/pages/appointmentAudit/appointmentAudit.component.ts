import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  signal,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { map } from 'rxjs';
import { LucideShield, LucideClipboardList } from '@lucide/angular';
import { AuditFilters, EMPTY_AUDIT_FILTERS } from '../../models/audit.model';
import { toAppointmentRow } from '../../models/audit.dto';
import { AuditTableComponent } from '../../components/auditTable/auditTable.component';
import { AuditFilterBarComponent } from '../../components/auditFilterBar/auditFilterBar.component';
import { AuditService } from '../../service/audit.service';

/**
 * Page de auditoría de citas: trae los logs de `AuditService`, los normaliza
 * a `AuditRow` con `toAppointmentRow` y aplica los filtros de
 * `AuditFilterBarComponent` antes de pasarlos a `AuditTableComponent`.
 */
@Component({
  selector: 'app-appointment-audit',
  standalone: true,
  imports: [
    LucideShield,
    LucideClipboardList,
    AuditTableComponent,
    AuditFilterBarComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './appointmentAudit.component.html',
})
export class AppointmentAuditComponent {
  private auditService = inject(AuditService);

  /** Filtros activos de búsqueda/estado/fecha, controlados por la filter bar. */
  protected filters = signal<AuditFilters>(EMPTY_AUDIT_FILTERS);

  /** Logs de auditoría de citas, ya normalizados a `AuditRow`. */
  private appointmentLogs = toSignal(
    this.auditService
      .getAppointmentLogs()
      .pipe(map((logs) => logs.map(toAppointmentRow))),
    { initialValue: [] }
  );

  /** Filas a mostrar en la tabla: `appointmentLogs` filtradas por `filters`. */
  protected rows = computed(() => {
    const f = this.filters();
    const q = f.search.toLowerCase();
    return this.appointmentLogs().filter(
      (row) =>
        (!q || row.name.toLowerCase().includes(q)) &&
        (f.status === 'all' || row.status === f.status) &&
        (!f.date || row.timestamp.startsWith(f.date))
    );
  });
}
