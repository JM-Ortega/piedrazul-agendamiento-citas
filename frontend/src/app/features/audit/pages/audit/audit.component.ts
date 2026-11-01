import {
  ChangeDetectionStrategy,
  Component,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { LucideShield } from '@lucide/angular';
import {
  AUDIT_PAGE_SIZE,
  AuditFilters,
  EMPTY_AUDIT_FILTERS,
} from '../../models/audit.model';
import { AuditTableComponent } from '../../components/auditTable/auditTable.component';
import { AuditFilterBarComponent } from '../../components/auditFilterBar/auditFilterBar.component';
import { PaginationComponent } from '../../../../designSystem/molecules/pagination/pagination.component';
import { AuditService } from '../../service/audit.service';
import { AppError } from '../../../../shared/models/interfaces/apiError.model';

/**
 * Consulta `AuditService` sobre toda la bitácora del sistema;
 * el módulo, la acción, el rango de fechas, el resultado y la
 * búsqueda son filtros que aplica el usuario.
 */
@Component({
  selector: 'app-audit',
  standalone: true,
  imports: [
    LucideShield,
    AuditTableComponent,
    AuditFilterBarComponent,
    PaginationComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './audit.component.html',
})
export class AuditComponent implements OnInit {
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
      error: (err: AppError) => {
        this.errorMessage.set(
          'No se pudieron cargar los filtros: ' + err.message
        );
      },
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
        pageNumber,
        pageSize: AUDIT_PAGE_SIZE,
      })
      .subscribe({
        error: (err: AppError) => {
          this.errorMessage.set(
            'No se pudieron cargar los registros: ' + err.message
          );
        },
      });
  }
}
