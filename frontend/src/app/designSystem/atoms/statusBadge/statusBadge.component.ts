import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { LucideCheckCircle, LucideXCircle } from '@lucide/angular';

/**
 * Badge de resultado ('Exitoso'/'Fallido').
 */
@Component({
  selector: 'app-status-badge',
  standalone: true,
  imports: [LucideCheckCircle, LucideXCircle],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './statusBadge.component.html',
})
export class StatusBadgeComponent {
  /** Resultado de la acción. */
  status = input.required<'success' | 'failed'>();
}
