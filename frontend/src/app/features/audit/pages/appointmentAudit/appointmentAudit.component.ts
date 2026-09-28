import {
  ChangeDetectionStrategy,
  Component,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { LucideShield, LucideClipboardList } from '@lucide/angular';
import {
  AUDIT_MODULE,
  AUDIT_PAGE_SIZE,
  AuditFilters,
  EMPTY_AUDIT_FILTERS,
} from '../../models/audit.model';
import { AuditTableComponent } from '../../components/auditTable/auditTable.component';
import { AuditFilterBarComponent } from '../../components/auditFilterBar/auditFilterBar.component';
import { PaginationComponent } from '../../../../designSystem/molecules/pagination/pagination.component';
import { AuditService } from '../../service/audit.service';

/**
 * Page de auditoría de citas: consulta a `AuditService` los registros del módulo `AUDIT_MODULE.appointments`
 */
@Component({
  selector: 'app-appointment-audit',
  standalone: true,
  imports: [
    LucideShield,
    LucideClipboardList,
    AuditTableComponent,
    AuditFilterBarComponent,
    PaginationComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './appointmentAudit.component.html',
})
export class AppointmentAuditComponent implements OnInit {
  private auditService = inject(AuditService);

  protected readonly events = this.auditService.events;
  protected readonly pagination = this.auditService.pagination;
  protected readonly catalog = this.auditService.catalog;
  protected readonly actionNames = this.auditService.actionNames;

  protected errorMessage = signal('');

  /** Filtros aplicados. Se conservan al cambiar de página; la barra mantiene aparte los que el usuario está editando. */
  private filters = signal<AuditFilters>(EMPTY_AUDIT_FILTERS);

  ngOnInit(): void {
    this.auditService.clearEvents();
    this.auditService.loadCatalog().subscribe({
      error: (err: { message: string }) =>
        this.errorMessage.set(
          'No se pudieron cargar los filtros: ' + err.message
        ),
    });
    this.loadEvents(0);
  }

  /** Aplica los filtros recibidos y vuelve a la primera página. */
  protected onApplyFilters(filters: AuditFilters): void {
    this.filters.set(filters);
    this.loadEvents(0);
  }

  /** Navega a la página indicada manteniendo los filtros aplicados. */
  protected onPageChange(pageNumber: number): void {
    this.loadEvents(pageNumber);
  }

  private loadEvents(pageNumber: number): void {
    this.errorMessage.set('');
    this.auditService
      .loadEvents({
        ...this.filters(),
        moduleCode: AUDIT_MODULE.appointments,
        pageNumber,
        pageSize: AUDIT_PAGE_SIZE,
      })
      .subscribe({
        error: (err: { message: string }) =>
          this.errorMessage.set(
            'No se pudieron cargar los registros: ' + err.message
          ),
      });
  }
}
