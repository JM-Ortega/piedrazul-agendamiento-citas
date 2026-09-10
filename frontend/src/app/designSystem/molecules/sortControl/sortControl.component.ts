import {
  ChangeDetectionStrategy,
  Component,
  input,
  output,
} from '@angular/core';
import { LucideArrowDown, LucideArrowUp } from '@lucide/angular';

export interface SortOption {
  value: string;
  label: string;
}

export type SortDirection = 'asc' | 'desc';

/**
 * Control compacto de ordenamiento: un select de atributo + un botón
 * independiente que alterna la dirección (ascendente/descendente),
 * mostrando el estado actual en texto e ícono.
 */
@Component({
  selector: 'app-sort-control',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [LucideArrowDown, LucideArrowUp],
  templateUrl: './sortControl.component.html',
})
export class SortControlComponent {
  options = input.required<SortOption[]>();
  field = input.required<string>();
  direction = input<SortDirection>('asc');

  fieldChange = output<string>();
  directionChange = output<SortDirection>();

  onFieldChange(value: string): void {
    this.fieldChange.emit(value);
  }

  toggleDirection(): void {
    this.directionChange.emit(this.direction() === 'asc' ? 'desc' : 'asc');
  }
}
