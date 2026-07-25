import { CommonModule } from '@angular/common';
import {
  Component,
  computed,
  EventEmitter,
  Input,
  input,
  Output,
} from '@angular/core';
import { RouterModule } from '@angular/router';

export type ButtonVariant =
  | 'primary' // azules de continuar, siguiente, editar...
  | 'secondary' // atras, retroceder (funcionalidad-pasos)...
  | 'success' // confirmar, guardar...
  | 'danger' // cancelar, salir, x (cerrar ventana)
  | 'icon' // logo de piedrazul (opcional si no existen mas botones así)
  | 'card' // modo de agendamiento (opcional si no hay mas iguales)
  | 'chip' // elegir la hora y filtros
  | 'link'; // volver atrás (paginas)

@Component({
  selector: 'app-button',
  standalone: true,
  imports: [CommonModule, RouterModule],
  templateUrl: './button.component.html',
})
export class ButtonComponent {
  @Input() variant: ButtonVariant = 'primary';
  @Input() size: 'sm' | 'md' | 'lg' = 'md';
  @Input() fullWidth = false;
  @Input() disabled = false;
  @Input() active = false;
  @Input() routerLink: string | string[] | null = null;
  @Input() href: string | null = null;
  @Input() type: 'button' | 'submit' | 'reset' = 'button';
  @Input() extraClass = '';
  @Output() buttonClick = new EventEmitter<void>();

  // loading pasa a ser un signal input: se sincroniza solo con el @Input,
  // sin necesidad de OnInit/OnChanges ni riesgo de quedar "atascado".
  loading = input(false);
  isLoading = computed(() => this.loading());

  get isDisabledLink(): boolean {
    return this.disabled && (!!this.routerLink || !!this.href);
  }

  getBaseClasses(): string {
    const sizeClasses = this.getSizeClasses();
    const variantClasses = this.getVariantClasses();
    const widthClass = this.fullWidth ? 'w-full' : '';
    const disabledClass = this.disabled
      ? 'opacity-50 cursor-not-allowed'
      : 'cursor-pointer';
    const transitionClass = 'transition-all duration-200';

    return `${sizeClasses} ${variantClasses} ${widthClass} ${disabledClass} ${transitionClass}`.trim();
  }

  private getSizeClasses(): string {
    switch (this.size) {
      case 'sm':
        return 'px-3 py-2 text-md';
      case 'md':
        return 'px-4 py-3 text-md';
      case 'lg':
        return 'px-5 py-4 text-lg';
      default:
        return 'px-6 py-3.5 text-base';
    }
  }

  private getVariantClasses(): string {
    const baseClasses = 'rounded-lg flex items-center justify-center gap-2';

    switch (this.variant) {
      case 'primary':
        return `${baseClasses} bg-[#215c98] text-white hover:bg-[#163c63] active:scale-95 rounded-lg `;

      case 'secondary':
        return `${baseClasses} bg-white border border-gray-300 text-gray-700 hover:bg-gray-50 rounded-lg`;

      case 'success':
        return `${baseClasses} bg-green-600 text-white hover:bg-green-700 active:scale-95 rounded-lg `;

      case 'danger':
        return `${baseClasses} bg-red-500 text-white hover:bg-red-600 active:scale-95 rounded-lg`;

      case 'icon':
        return `${baseClasses} bg-transparent text-gray-600 hover:bg-gray-100 w-9 h-9 p-0 rounded-lg`;

      case 'link':
        return `${baseClasses} bg-transparent text-[#215c98] hover:text-[#163c63] underline`;

      case 'card':
        return `${baseClasses} bg-white rounded-xl shadow-lg px-12 py-7 text-left text-[#215c98] hover:scale-105 active:scale-95 mt-4`;

      default:
        return baseClasses;
    }
  }

  // Nota: 'chip' se maneja aparte en getChipClasses() porque tiene estado
  // propio (active) distinto al resto de variantes. Candidato a extraerse
  // a su propio componente ChipComponent más adelante.
  getChipClasses(): string {
    const base =
      'py-3 rounded-xl border-2 text-base font-semibold transition-colors cursor-pointer';

    if (this.active) {
      return `${base} border-[#4e92d9] bg-[#a7c9ec] text-white`;
    }

    return `${base} border-gray-200 hover:border-[#4e92d9] hover:bg-[#dbeafe] text-[#163c63]`;
  }

  onClick(): void {
    if (!this.disabled && !this.isLoading()) {
      this.buttonClick.emit();
    }
  }

  isClickable(): boolean {
    return !this.disabled && !this.isLoading();
  }
}
