import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { LucideBan, LucideCheckCircle, LucideXCircle } from '@lucide/angular';

/**
 * Badge de resultado ('Exitoso' / 'Fallido' / 'Denegado')
 */
@Component({
  selector: 'app-status-badge',
  standalone: true,
  imports: [LucideCheckCircle, LucideXCircle, LucideBan],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './statusBadge.component.html',
})
export class StatusBadgeComponent {
  status = input.required<'EXITOSO' | 'FALLIDO' | 'DENEGADO'>();
}
