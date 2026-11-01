import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  inject,
  input,
  output,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { LucideSearch, LucideX } from '@lucide/angular';
import { debounceTime, distinctUntilChanged, map } from 'rxjs';

/**
 * Molécula de búsqueda reutilizable: input con debounce + búsqueda
 * inmediata (Enter o botón) + botón de limpiar + indicador de carga.
 *
 * Genérico — no depende de ningún dominio (doctores, pacientes, usuarios).
 * El componente padre decide qué hacer con el término emitido.
 */
@Component({
  selector: 'app-search-input',
  standalone: true,
  imports: [ReactiveFormsModule, LucideSearch, LucideX],
  templateUrl: './searchInput.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block flex-1 min-w-0' },
})
export class SearchInputComponent {
  /** Texto de ayuda del input. */
  placeholder = input('Buscar...');
  /** Tiempo de espera (ms) tras dejar de escribir, antes de emitir `search`. */
  debounceMs = input(400);
  /** Si es true, muestra un spinner en vez del ícono de lupa. */
  loading = input(false);
  /** Texto del botón de búsqueda inmediata. */
  buttonLabel = input('Buscar');
  /** Si es false, oculta el botón "Buscar" y deja solo el input con debounce. */
  showButton = input(true);

  /** Tamaño visual: 'md' (por defecto, con glow y botón) o 'sm' (compacto, pill simple). */
  size = input<'sm' | 'md'>('md');

  /** Se emite tras el debounce, con el valor recortado (trim). */
  searchChange = output<string>();
  /** Se emite de inmediato al presionar Enter o el botón de búsqueda. */
  searchNow = output<string>();

  control = new FormControl('', { nonNullable: true });
  private destroyRef = inject(DestroyRef);

  constructor() {
    this.control.valueChanges
      .pipe(
        debounceTime(this.debounceMs()),
        map((v) => v.trim()),
        distinctUntilChanged(),
        takeUntilDestroyed(this.destroyRef)
      )
      .subscribe((value) => this.searchChange.emit(value));
  }

  onSearchNow(): void {
    this.searchNow.emit(this.control.value.trim());
  }

  clear(): void {
    this.control.setValue('');
    this.searchChange.emit('');
  }
}
