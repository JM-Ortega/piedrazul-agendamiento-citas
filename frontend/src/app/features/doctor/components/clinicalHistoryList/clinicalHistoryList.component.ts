import {
  ChangeDetectionStrategy,
  Component,
  input,
  output,
} from '@angular/core';
import { LucideCalendar, LucideFolderOpen } from '@lucide/angular';
import { PaginationComponent } from '../../../../designSystem/molecules/pagination/pagination.component';
import { PaginationMeta } from '../../../../shared/helpers/paginatedState';
import { MedicalRecord } from '../../../../shared/models/dtos/medicalRecord.dto';

/**
 * Lista paginada del historial de consultas (controles médicos) de un
 * paciente. Es presentacional: recibe los registros ya filtrados, la
 * metadata de paginación y el estado de carga, y emite `pageChange` cuando
 * el usuario cambia de página. El padre decide de dónde salen los datos y
 * cómo se dispone en el layout (ancho, scroll).
 */
@Component({
  selector: 'app-clinical-history-list',
  templateUrl: './clinicalHistoryList.component.html',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  imports: [LucideFolderOpen, LucideCalendar, PaginationComponent],
})
export class ClinicalHistoryListComponent {
  // ── Inputs / Outputs ──────────────────────────────────────────────────────
  records = input.required<MedicalRecord[]>();
  pagination = input<PaginationMeta | null>(null);
  loading = input(false);
  /** Nueva página solicitada (base 0). */
  pageChange = output<number>();
}
